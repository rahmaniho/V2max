#!/usr/bin/env python3
import importlib.util
import os
import pathlib
import subprocess
import sys
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

    def test_native_badging_must_be_present_and_unambiguous(self):
        badging = "package: example\nnative-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'\n"
        self.assertEqual({"arm64-v8a", "armeabi-v7a", "x86_64"}, metadata.native_abis(badging))
        for malformed in ("", "native-code: \n", badging + "native-code: 'x86'\n", "native-code: 'unterminated\n"):
            with self.subTest(badging=malformed), self.assertRaises(ValueError):
                metadata.native_abis(malformed)

    def test_universal_and_splits_must_support_their_advertised_abis(self):
        abis = {name: set(required) for name, required in metadata.REQUIRED_ABIS.items()}
        metadata.verify_abis(self.root, lambda path: abis[path.name])
        for name, expected in metadata.REQUIRED_ABIS.items():
            for missing in expected:
                broken = {key: value.copy() for key, value in abis.items()}
                broken[name].remove(missing)
                with self.subTest(name=name, missing=missing), self.assertRaisesRegex(ValueError, name):
                    metadata.verify_abis(self.root, lambda path: broken[path.name])

    def test_cli_checks_real_aapt_output_for_each_apk(self):
        build_tools = self.root / "sdk" / "build-tools" / "35.0.0"
        build_tools.mkdir(parents=True)
        aapt = build_tools / "aapt"
        aapt.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
name = pathlib.Path(sys.argv[-1]).name
abis = {
    'app-arm64-v8a-release.apk': ['arm64-v8a'],
    'app-armeabi-v7a-release.apk': ['armeabi-v7a'],
    'app-universal-release.apk': ['arm64-v8a', 'armeabi-v7a', 'x86_64'],
    'app-x86_64-release.apk': ['x86_64'],
}[name]
if os.environ.get('TEST_INCOMPLETE') and name == 'app-universal-release.apk':
    abis.remove('armeabi-v7a')
print("package: name='ir.v2max.app' versionCode='{}' versionName='{}'".format(
    os.environ['TEST_CODE'], os.environ['TEST_VERSION']))
print('native-code: ' + ' '.join("'" + abi + "'" for abi in abis))
''', encoding="utf-8")
        aapt.chmod(0o755)
        version, code = metadata.gradle_version("app/build.gradle.kts")
        env = dict(os.environ, ANDROID_HOME=str(self.root / "sdk"), TEST_VERSION=version, TEST_CODE=str(code))
        env.pop("GITHUB_OUTPUT", None)
        command = [sys.executable, str(pathlib.Path(__file__).with_name("verify-release-metadata.py")), str(self.root)]
        subprocess.run(command, check=True, env=env, stdout=subprocess.DEVNULL)
        env["TEST_INCOMPLETE"] = "1"
        failed = subprocess.run(command, env=env, text=True, capture_output=True)
        self.assertNotEqual(0, failed.returncode)
        self.assertIn("missing required native architectures", failed.stderr)

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
