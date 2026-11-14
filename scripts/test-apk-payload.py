#!/usr/bin/env python3
import importlib.util
import pathlib
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("apk_payload", pathlib.Path(__file__).with_name("verify-apk-payload.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class PayloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = pathlib.Path(self.temp.name)
        self.before, self.after = root / "before", root / "after"
        self.before.mkdir()
        self.after.mkdir()
        for name in checker.REQUIRED_APKS:
            self.archive(self.before / name, "old")
            self.archive(self.after / name, "new")

    @staticmethod
    def archive(path, signature, dex=b"tested", metadata=b"library metadata", duplicate=False):
        with warnings.catch_warnings(), zipfile.ZipFile(path, "w") as z:
            warnings.simplefilter("ignore", UserWarning)
            z.writestr("classes.dex", dex)
            z.writestr("META-INF/library.kotlin_module", metadata)
            z.writestr("META-INF/MANIFEST.MF", signature)
            z.writestr("META-INF/CERT.SF", signature)
            z.writestr("META-INF/CERT.RSA", signature)
            if duplicate:
                z.writestr("classes.dex", b"ambiguous")

    def test_signatures_can_change(self):
        checker.verify(self.before, self.after)

    def test_modified_dex_and_non_signature_metadata_fail(self):
        path = self.after / "app-universal-release.apk"
        for kwargs in ({"dex": b"tampered"}, {"metadata": b"changed"}, {"duplicate": True}):
            self.archive(path, "new", **kwargs)
            with self.assertRaises(ValueError):
                checker.verify(self.before, self.after)

    def test_missing_or_extra_apk_fails(self):
        path = self.after / "app-universal-release.apk"
        path.unlink()
        with self.assertRaises(ValueError):
            checker.verify(self.before, self.after)
        self.archive(path, "new")
        self.archive(self.after / "unexpected.apk", "new")
        with self.assertRaises(ValueError):
            checker.verify(self.before, self.after)


if __name__ == "__main__":
    unittest.main()
