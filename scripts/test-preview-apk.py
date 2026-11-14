#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("preview_apk", pathlib.Path(__file__).with_name("verify-preview-apk.py"))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)

BADGING = """package: name='ir.v2max.app.preview' versionCode='23' versionName='2.4.0-preview' platformBuildVersionName='15'
application-label:'V2max آزمایشی'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
"""


class PreviewApkTest(unittest.TestCase):
    def test_expected_badging(self):
        preview.verify_badging(BADGING, "2.4.0", 23)

    def test_unsafe_or_incompatible_badging_is_rejected(self):
        changes = (
            BADGING.replace("ir.v2max.app.preview", "ir.v2max.app"),
            BADGING.replace("versionCode='23'", "versionCode='22'"),
            BADGING.replace("2.4.0-preview", "2.4.0"),
            BADGING.replace("V2max آزمایشی", "V2max"),
            BADGING + "application-debuggable\n",
            BADGING.replace(" 'arm64-v8a'", ""),
        )
        for badging in changes:
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                preview.verify_badging(badging, "2.4.0", 23)

    def test_signer_is_read_and_official_signer_is_rejected(self):
        test_cert = "ab" * 32
        for output in (
            f"Signer #1 certificate SHA-256 digest: {test_cert}\n",
            f"V2 Signer: certificate SHA-256 digest: {test_cert}\n",
            f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {test_cert}\n"
            f"V3 Signer: certificate SHA-256 digest: {test_cert}\n",
        ):
            self.assertEqual(test_cert, preview.verify_signer(output))
        for output in (
            "",
            "Signer #1 certificate SHA-256 digest: abcd\n",
            f"Signer #1 certificate SHA-256 digest: {preview.OFFICIAL_CERT}\n",
            f"Signer #1 certificate SHA-256 digest: {test_cert}\nSigner #2 certificate SHA-256 digest: {'cd' * 32}\n",
            f"Number of signers: 2\nSigner #1 certificate SHA-256 digest: {test_cert}\n",
        ):
            with self.subTest(output=output), self.assertRaises(ValueError):
                preview.verify_signer(output)


if __name__ == "__main__":
    unittest.main()
