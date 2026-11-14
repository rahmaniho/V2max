#!/usr/bin/env python3
import copy
import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("preview_release", pathlib.Path(__file__).with_name("verify-preview-release.py"))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class PreviewReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = pathlib.Path(self.tmp.name) / preview.ASSET_NAME
        self.apk.write_bytes(b"isolated preview fixture")
        self.tag = "v2.4.0-preview.12345.1"
        self.release = {
            "tag_name": self.tag, "draft": True, "prerelease": True,
            "assets": [{"name": preview.ASSET_NAME, "state": "uploaded",
                        "size": self.apk.stat().st_size, "digest": preview.digest(self.apk)}],
        }

    def test_draft_and_published_release(self):
        preview.verify(self.release, self.apk, self.tag, True)
        self.release["draft"] = False
        preview.verify(self.release, self.apk, self.tag, False)

    def test_wrong_release_state_or_asset_is_rejected(self):
        changes = []
        for field, value in (("tag_name", "v2.4.0"), ("draft", False), ("prerelease", False), ("assets", [])):
            altered = copy.deepcopy(self.release)
            altered[field] = value
            changes.append(altered)
        for field, value in (("name", "app-universal-release.apk"), ("state", "new"),
                             ("size", 1), ("digest", "sha256:" + "00" * 32)):
            altered = copy.deepcopy(self.release)
            altered["assets"][0][field] = value
            changes.append(altered)
        doubled = copy.deepcopy(self.release)
        doubled["assets"].append(copy.deepcopy(doubled["assets"][0]))
        changes.append(doubled)
        for altered in changes:
            with self.subTest(release=altered), self.assertRaises(ValueError):
                preview.verify(altered, self.apk, self.tag, True)
        with self.assertRaises(ValueError):
            preview.verify(self.release, self.apk, "v2.4.0", True)


if __name__ == "__main__":
    unittest.main()
