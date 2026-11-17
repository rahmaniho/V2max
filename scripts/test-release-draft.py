#!/usr/bin/env python3
import copy
import importlib.util
import itertools
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("draft", pathlib.Path(__file__).with_name("verify-release-draft.py"))
draft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(draft)


class DraftTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.release = {"tag_name": "v2.4.0", "draft": True, "prerelease": False, "assets": []}
        for name in draft.REQUIRED_APKS:
            (self.root / name).write_bytes(name.encode())
        # Simulate GitHub's alphabetically ordered release API, not upload order.
        for name, local_name in sorted(draft.PUBLISHED_APKS.items()):
            path = self.root / local_name
            self.release["assets"].append({
                "name": name, "size": path.stat().st_size, "state": "uploaded", "digest": draft.digest(path),
                "browser_download_url": f"https://github.com/rahmaniho/V2max/releases/download/v2.4.0/{name}",
            })

    def check(self, release):
        draft.verify(release, self.root, "v2.4.0")

    def test_matching_draft_passes(self):
        self.check(self.release)

    def test_cli_accepts_a_string_directory_for_draft_and_published(self):
        release_json = self.root / "release.json"
        for state in ("draft", "published"):
            release = copy.deepcopy(self.release)
            release["draft"] = state == "draft"
            release_json.write_text(json.dumps(release), encoding="utf-8")
            subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("verify-release-draft.py")),
                            str(release_json), str(self.root), "v2.4.0", state], check=True,
                           stdout=subprocess.DEVNULL)

    def test_published_state_must_be_explicit(self):
        with self.assertRaises(ValueError):
            draft.verify(self.release, self.root, "v2.4.0", draft=False)
        published = copy.deepcopy(self.release)
        published["draft"] = False
        draft.verify(published, self.root, "v2.4.0", draft=False)
        with self.assertRaises(ValueError):
            self.check(published)

    def test_draft_url_can_be_untagged_but_published_url_must_be_official(self):
        item = copy.deepcopy(self.release)
        item["assets"][0]["browser_download_url"] = (
            "https://github.com/rahmaniho/V2max/releases/download/untagged-123/00-V2max-universal.apk"
        )
        self.check(item)
        item["draft"] = False
        with self.assertRaisesRegex(ValueError, "00-V2max-universal.apk"):
            draft.verify(item, self.root, "v2.4.0", draft=False)

    def test_all_orders_pass_after_universal_alias(self):
        for other_assets in itertools.permutations(self.release["assets"][1:]):
            item = copy.deepcopy(self.release)
            item["assets"] = [item["assets"][0], *other_assets]
            with self.subTest(order=[asset["name"] for asset in item["assets"]]):
                self.check(item)

    def test_v231_receives_the_universal_alias(self):
        # This is the first-APK selection performed by the v2.3.1 updater.
        selected = next(asset for asset in self.release["assets"] if asset["name"].endswith(".apk"))
        self.assertEqual(draft.LEGACY_UNIVERSAL_APK, selected["name"])
        canonical = self.root / draft.UNIVERSAL_APK
        self.assertEqual(draft.digest(canonical), selected["digest"])
        self.assertEqual(canonical.stat().st_size, selected["size"])

    def test_any_other_first_asset_is_rejected(self):
        for index in range(1, len(self.release["assets"])):
            item = copy.deepcopy(self.release)
            item["assets"][0], item["assets"][index] = item["assets"][index], item["assets"][0]
            selected = item["assets"][0]["name"]
            with self.subTest(selected=selected), self.assertRaisesRegex(ValueError, "Universal compatibility APK must be first"):
                self.check(item)

    def test_universal_alias_must_match_canonical_signed_apk(self):
        item = copy.deepcopy(self.release)
        item["assets"][0]["digest"] = draft.digest(self.root / "app-arm64-v8a-release.apk")
        with self.assertRaisesRegex(ValueError, "00-V2max-universal.apk"):
            self.check(item)

    def test_missing_modified_duplicate_and_unuploaded_assets_fail(self):
        variants = []
        for operation in ("missing", "duplicate", "digest", "size", "state"):
            item = copy.deepcopy(self.release)
            if operation == "missing": item["assets"].pop()
            if operation == "duplicate": item["assets"][-1] = copy.deepcopy(item["assets"][0])
            if operation == "digest": item["assets"][0]["digest"] = "sha256:wrong"
            if operation == "size": item["assets"][0]["size"] += 1
            if operation == "state": item["assets"][0]["state"] = "new"
            variants.append(item)
        for item in variants:
            with self.assertRaises(ValueError):
                self.check(item)

    def test_not_a_stable_draft_fails(self):
        for key, value in (("draft", False), ("prerelease", True), ("tag_name", "v2.5.0")):
            item = copy.deepcopy(self.release)
            item[key] = value
            with self.assertRaises(ValueError):
                self.check(item)


if __name__ == "__main__":
    unittest.main()
