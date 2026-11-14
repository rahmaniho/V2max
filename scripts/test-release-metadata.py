#!/usr/bin/env python3
import importlib.util
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("metadata", pathlib.Path(__file__).with_name("verify-release-metadata.py"))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        for name in metadata.REQUIRED_APKS:
            (self.root / name).touch()
        self.previous = self.root.parent / "previous.apk"
        self.values = {name: (metadata.PACKAGE, 23, "2.4.0") for name in metadata.REQUIRED_APKS}
        self.values["previous.apk"] = (metadata.PACKAGE, 22, "2.3.1")

    def read(self, path):
        return self.values[path.name]

    def test_matches_all_apks_and_advances_both_versions(self):
        metadata.verify(self.root, "2.4.0", 23, self.read, self.previous)

    def test_wrong_package_or_version_in_any_split_fails(self):
        for value in (("other", 23, "2.4.0"), (metadata.PACKAGE, 24, "2.4.0"), (metadata.PACKAGE, 23, "2.3.1")):
            self.values["app-arm64-v8a-release.apk"] = value
            with self.assertRaises(ValueError):
                metadata.verify(self.root, "2.4.0", 23, self.read)

    def test_incompatible_or_nonincreasing_previous_fails(self):
        for value in (("other", 22, "2.3.1"), (metadata.PACKAGE, 23, "2.3.1"),
                      (metadata.PACKAGE, 22, "2.4.0"), (metadata.PACKAGE, 22, "2.4.0-rc")):
            self.values["previous.apk"] = value
            with self.assertRaises(ValueError):
                metadata.verify(self.root, "2.4.0", 23, self.read, self.previous)

    def test_build_version_and_identity_must_be_unambiguous(self):
        build = self.root / "build.gradle.kts"
        build.write_text('val appVersionCode = 23\nversionName = "2.4.0"\n', encoding="utf-8")
        self.assertEqual(("2.4.0", 23), metadata.gradle_version(build))
        for text in ('val appVersionCode = 22\nversionName = "2.4.0"\n',
                     'val appVersionCode = 23\nversionName = "2.4.0-rc"\n',
                     'val appVersionCode = 23\nversionName = "2.4.0"\nversionName = "2.5.0"\n'):
            build.write_text(text, encoding="utf-8")
            with self.assertRaises(ValueError):
                metadata.gradle_version(build)


if __name__ == "__main__":
    unittest.main()
