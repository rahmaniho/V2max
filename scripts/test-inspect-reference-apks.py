#!/usr/bin/env python3
"""Fail-closed checks for the static APK inspector, with no network or APKs."""

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "inspect-reference-apks.sh"
SIGNER = "b7dc883e7678d6c618569e48a1c8c267a134a75f8bcb763510c8bffb3b90b077"
STABLE_URL = "https://github.com/2dust/v2rayNG/releases/download/2.3.15/v2rayNG_2.3.15_arm64-v8a.apk"


def executable(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(text).lstrip(), encoding="utf-8")
    path.chmod(0o755)


class ReferenceInspectorTest(unittest.TestCase):
    def run_inspector(self, fault=""):
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            commands = temp / "bin"
            sdk = temp / "sdk"
            trace = temp / "trace"
            executable(commands / "curl", """
                #!/usr/bin/env python3
                import os, pathlib, sys
                args = sys.argv[1:]
                with open(os.environ['TEST_TRACE'], 'a') as f: f.write(args[-1] + '\\n')
                pathlib.Path(args[args.index('-o') + 1]).write_bytes(b'disposable fixture')
            """)
            executable(commands / "adb", """
                #!/usr/bin/env python3
                import os, sys
                with open(os.environ['TEST_TRACE'], 'a') as f: f.write('ADB_INVOKED\\n')
                sys.exit(99)
            """)
            executable(commands / "sha256sum", """
                #!/usr/bin/env python3
                import os, sys
                line = sys.stdin.read()
                failed = os.environ.get('FAULT') == 'digest' and 'v2rayNG-stable.apk' in line
                sys.exit(1 if failed else 0)
            """)
            executable(commands / "unzip", """
                #!/usr/bin/env python3
                import os, sys
                stable = 'v2rayNG-stable.apk' in sys.argv[-1]
                if not (stable and os.environ.get('FAULT') == 'arch'):
                    print('lib/arm64-v8a/libgojni.so')
                print('classes.dex')
            """)
            executable(sdk / "build-tools" / "35.0.0" / "aapt", """
                #!/usr/bin/env python3
                import os, sys
                stable = 'v2rayNG-stable.apk' in sys.argv[-1]
                package = 'wrong.package' if stable and os.environ.get('FAULT') == 'package' else 'com.v2ray.ang'
                version = '9.9.9' if stable and os.environ.get('FAULT') == 'version' else '2.3.15'
                print(f"package: name='{package}' versionCode='123' versionName='{version}'")
                print("sdkVersion:'24'")
                print("targetSdkVersion:'35'")
            """)
            executable(sdk / "build-tools" / "35.0.0" / "apksigner", f"""
                #!/usr/bin/env python3
                import os, sys
                stable = 'v2rayNG-stable.apk' in sys.argv[-1]
                signer = '0' * 64 if stable and os.environ.get('FAULT') == 'signer' else '{SIGNER}'
                print('Signer #1 certificate SHA-256 digest: ' + signer)
                if stable and os.environ.get('FAULT') == 'extra-signer':
                    print('Signer #2 certificate SHA-256 digest: ' + '1' * 64)
            """)
            env = dict(os.environ, ANDROID_HOME=str(sdk), TEST_TRACE=str(trace), FAULT=fault,
                       PATH=f"{commands}{os.pathsep}{os.environ['PATH']}")
            result = subprocess.run(["bash", str(SCRIPT)], cwd=ROOT, env=env,
                                    text=True, capture_output=True, timeout=20)
            urls = trace.read_text(encoding="utf-8").splitlines() if trace.exists() else []
            return result, urls

    def test_valid_official_arm64_asset_is_inspected_without_installation(self):
        result, urls = self.run_inspector()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(STABLE_URL, urls)
        self.assertIn("v2rayNG-stable APK metadata", result.stdout)
        self.assertIn("v2rayNG-stable APK signing", result.stdout)
        self.assertNotIn("ADB_INVOKED", urls)

    def test_digest_package_version_arch_and_signers_fail_closed(self):
        for fault in ("digest", "package", "version", "arch", "signer", "extra-signer"):
            with self.subTest(fault=fault):
                result, urls = self.run_inspector(fault)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(STABLE_URL, urls)
                self.assertNotIn("v2rayNG-stable APK metadata", result.stdout)


if __name__ == "__main__":
    unittest.main()
