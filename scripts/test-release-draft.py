#!/usr/bin/env python3
import copy
import importlib.util
import pathlib
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
        for name in sorted(draft.REQUIRED_APKS):
            path = self.root / name
            path.write_bytes(name.encode())
            self.release["assets"].append({"name": name, "size": path.stat().st_size,
                                           "state": "uploaded", "digest": draft.digest(path)})

    def check(self, release):
        draft.verify(release, self.root, "v2.4.0")

    def test_matching_draft_passes(self):
        self.check(self.release)

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
