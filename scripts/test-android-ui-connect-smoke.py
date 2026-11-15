#!/usr/bin/env python3
"""Exercise the emulator-only UI runner with a fake adb, without a device."""

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "run-android-ui-connect-smoke.sh"

FAKE_ADB = r'''#!/usr/bin/env python3
import os, pathlib, shlex, sys
args = sys.argv[1:]
with pathlib.Path(os.environ['ADB_TRACE']).open('a', encoding='utf-8') as trace:
    trace.write(shlex.join(args) + '\n')
if args == ['shell', 'getprop', 'ro.kernel.qemu']:
    print(os.environ.get('MOCK_QEMU', '1'))
elif args == ['shell', 'getprop', 'ro.build.version.sdk']:
    print(os.environ.get('MOCK_API', '30'))
elif args[:3] == ['shell', 'pm', 'path']:
    if args[3] == os.environ.get('MOCK_MISSING'):
        sys.exit(1)
    print('package:/data/app/test.apk')
elif args[:3] == ['shell', 'pm', 'clear-permission-flags'] and int(os.environ.get('MOCK_API', '30')) < 33:
    print('Unknown command: clear-permission-flags')
    sys.exit(1)
elif args[:3] == ['shell', 'am', 'instrument']:
    print(os.environ.get('MOCK_RESULT', 'OK (12 tests)'))
    sys.exit(int(os.environ.get('MOCK_INSTRUMENT_EXIT', '0')))
elif args[:4] == ['logcat', '-b', 'crash', '-d']:
    print(os.environ.get('MOCK_CRASH', ''))
'''


class AndroidUiSmokeRunnerTest(unittest.TestCase):
    def run_smoke(self, target="debug", **options):
        with tempfile.TemporaryDirectory() as tmp:
            temp = Path(tmp)
            adb = temp / "adb"
            adb.write_text(FAKE_ADB, encoding="utf-8")
            adb.chmod(0o755)
            trace = temp / "adb-trace"
            env = dict(os.environ, PATH=f"{temp}{os.pathsep}{os.environ['PATH']}", ADB_TRACE=str(trace))
            env.update({f"MOCK_{key.upper()}": str(value) for key, value in options.items()})
            result = subprocess.run(["bash", str(SCRIPT), target], cwd=ROOT, env=env,
                                    text=True, capture_output=True, timeout=20)
            commands = [shlex.split(line) for line in trace.read_text(encoding="utf-8").splitlines()]
            return result, commands

    def test_api_30_resets_camera_without_unsupported_flag_command(self):
        result, commands = self.run_smoke(api=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(['shell', 'pm', 'revoke', 'ir.v2max.app.debug', 'android.permission.CAMERA'], commands)
        self.assertFalse(any(c[:3] == ['shell', 'pm', 'clear-permission-flags'] for c in commands))
        self.assertFalse(any('android.permission.POST_NOTIFICATIONS' in c for c in commands))
        instrument = next(i for i, c in enumerate(commands) if c[:3] == ['shell', 'am', 'instrument'])
        self.assertLess(commands.index(['shell', 'pm', 'revoke', 'ir.v2max.app.debug', 'android.permission.CAMERA']), instrument)

    def test_api_35_resets_both_permissions_before_preview_instrumentation(self):
        result, commands = self.run_smoke("preview", api=35)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        instrument = next(i for i, c in enumerate(commands) if c[:3] == ['shell', 'am', 'instrument'])
        for permission in ('android.permission.CAMERA', 'android.permission.POST_NOTIFICATIONS'):
            revoke = ['shell', 'pm', 'revoke', 'ir.v2max.app.preview', permission]
            flags = ['shell', 'pm', 'clear-permission-flags', 'ir.v2max.app.preview', permission, 'user-set', 'user-fixed']
            self.assertLess(commands.index(revoke), commands.index(flags))
            self.assertLess(commands.index(flags), instrument)
        self.assertIn('ir.v2max.app.preview.test/androidx.test.runner.AndroidJUnitRunner', commands[instrument])

    def test_refuses_a_physical_device_before_modifying_anything(self):
        result, commands = self.run_smoke(qemu=0)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([['shell', 'getprop', 'ro.kernel.qemu']], commands)

    def test_missing_test_package_does_not_change_permissions(self):
        result, commands = self.run_smoke(missing='ir.v2max.app.debug.test')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Required emulator package is not installed', result.stdout)
        self.assertFalse(any(c[:3] == ['shell', 'appops', 'set'] for c in commands))
        self.assertFalse(any(c[:3] == ['shell', 'am', 'instrument'] for c in commands))

    def test_incomplete_run_and_crash_do_not_pass(self):
        result, commands = self.run_smoke(result='OK (11 tests)', crash='FATAL EXCEPTION: main\nexample crash')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Android UI process crash', result.stdout)
        self.assertFalse('::notice title=Android UI connection smoke' in result.stdout)
        self.assertTrue(any(c[:4] == ['logcat', '-b', 'crash', '-d'] for c in commands))


if __name__ == '__main__':
    unittest.main()
