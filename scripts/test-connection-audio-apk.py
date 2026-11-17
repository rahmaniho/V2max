#!/usr/bin/env python3
import importlib.util
import pathlib
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("connection_audio", pathlib.Path(__file__).with_name("verify-connection-audio.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ConnectionAudioApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "test.apk"
        self.recording = b"ID3-tested-recording"

    def write_apk(self, name="res/obfuscated.bin", audio=None, compression=zipfile.ZIP_STORED, duplicate=False):
        with warnings.catch_warnings(), zipfile.ZipFile(self.path, "w") as archive:
            warnings.simplefilter("ignore", UserWarning)
            archive.writestr(name, self.recording if audio is None else audio, compress_type=compression)
            if duplicate:
                archive.writestr(name, self.recording)

    def test_exact_uncompressed_resource_survives_renaming(self):
        self.write_apk()
        checker.verify_apk(self.path, self.recording)

    def test_missing_changed_compressed_and_ambiguous_recordings_fail(self):
        for kwargs in (
            {"name": "assets/voice.mp3"},
            {"audio": b"ID3-changed-recording"},
            {"compression": zipfile.ZIP_DEFLATED},
            {"duplicate": True},
        ):
            with self.subTest(kwargs=kwargs):
                self.write_apk(**kwargs)
                with self.assertRaises(ValueError):
                    checker.verify_apk(self.path, self.recording)


if __name__ == "__main__":
    unittest.main()
