#!/usr/bin/env python3
"""Exercise the stable release shell with fake GitHub responses, never real publication."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The fake gh only reads test files and records calls in a temporary directory.
MOCK_GH = r'''#!/usr/bin/env python3
import copy, hashlib, json, os, pathlib, sys
args = sys.argv[1:]
root = pathlib.Path(os.environ['RUNNER_TEMP'])
state_path = root / 'mock-release.json'
log_path = root / 'mock-gh.log'
with log_path.open('a') as log:
    log.write(json.dumps(args) + '\n')
scenario = os.environ.get('MOCK_SCENARIO', 'success')
repo = os.environ['GITHUB_REPOSITORY']
sha = os.environ['GITHUB_SHA']

def load():
    return json.loads(state_path.read_text())

def emit(obj):
    print(json.dumps(obj))

if args[:2] == ['release', 'download']:
    target = pathlib.Path(args[args.index('--dir') + 1])
    target.mkdir(parents=True, exist_ok=True)
    (target / 'app-arm64-v8a-release.apk').write_bytes(b'previous')
elif args[:2] == ['api', '--method']:
    if args[2] != 'POST' or not args[3].endswith('/git/refs'):
        sys.exit('unexpected POST')
elif args[:2] == ['release', 'create']:
    tag = args[2]
    paths = []
    for value in args[3:]:
        if value.startswith('--'):
            break
        paths.append(pathlib.Path(value))
    if len(paths) != 5 or '--draft' not in args or '--verify-tag' not in args:
        sys.exit('unexpected draft command')
    assets = []
    for path in paths:
        content = path.read_bytes()
        assets.append({'name': path.name, 'size': len(content), 'state': 'uploaded',
                       'digest': 'sha256:' + hashlib.sha256(content).hexdigest(),
                       'browser_download_url': f'https://github.com/{repo}/releases/download/untagged-123/{path.name}'})
    assets.sort(key=lambda a: a['name'])
    if scenario == 'bad-draft-order':
        assets[0], assets[1] = assets[1], assets[0]
    if scenario == 'bad-alias-digest':
        assets[0]['digest'] = 'sha256:' + '0' * 64
    state_path.write_text(json.dumps({'id': 123, 'tag_name': tag, 'draft': True,
                                     'prerelease': False, 'assets': assets}))
elif args[:2] == ['release', 'view']:
    print(f'https://api.github.com/repos/{repo}/releases/123')
elif args[:2] == ['release', 'edit']:
    release = load()
    if '--draft=false' not in args or '--latest' not in args:
        sys.exit('unexpected publication command')
    release['draft'] = False
    for asset in release['assets']:
        asset['browser_download_url'] = f"https://github.com/{repo}/releases/download/{release['tag_name']}/{asset['name']}"
    if scenario == 'bad-published-url':
        release['assets'][0]['browser_download_url'] = 'https://example.org/wrong.apk'
    state_path.write_text(json.dumps(release))
elif args and args[0] == 'api':
    endpoint = args[1]
    if endpoint.endswith('/git/ref/heads/main'):
        calls = root / 'main-calls'
        count = int(calls.read_text()) + 1 if calls.exists() else 1
        calls.write_text(str(count))
        print('b' * 40 if scenario == 'main-moved' and count > 1 else sha)
    elif '/git/ref/tags/' in endpoint:
        print(sha)
    elif endpoint.endswith('/releases/latest') and '--jq' in args:
        print('v2.3.1')
    elif endpoint.endswith('/releases/latest'):
        emit(load())
    elif endpoint.endswith('/releases/123'):
        emit(load())
    elif '/releases?per_page=' in endpoint:
        release = load()
        if scenario == 'bad-list-order' and release['draft']:
            release = copy.deepcopy(release)
            release['assets'][0], release['assets'][1] = release['assets'][1], release['assets'][0]
        if scenario == 'bad-published-list' and not release['draft']:
            release = copy.deepcopy(release)
            release['assets'][0], release['assets'][1] = release['assets'][1], release['assets'][0]
        emit(release)
    else:
        sys.exit('unexpected api endpoint: ' + endpoint)
else:
    sys.exit('unexpected gh command: ' + repr(args))
'''


def publication_script():
    source = (ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8')
    match = re.search(r'(?ms)^      - name: Publish complete numbered release\n.*?^        run: \|\n(.*?)(?=^  publish-preview:)', source)
    if not match:
        raise AssertionError('Publication shell block was not found')
    return textwrap.dedent(match.group(1))


class ApprovalWorkflowTest(unittest.TestCase):
    def test_only_explicit_true_allows_release(self):
        source = (ROOT / '.github/workflows/build.yml').read_text(encoding='utf-8')
        match = re.search(r'(?ms)^      - name: Require owner security review approval\n.*?^        run: \|\n(.*?)(?=^  sign-release:)', source)
        self.assertIsNotNone(match)
        script = textwrap.dedent(match.group(1))
        for value in (None, '', 'false', 'TRUE', '1', 'true ', 'true'):
            env = os.environ.copy()
            if value is None:
                env.pop('RELEASE_APPROVED', None)
            else:
                env['RELEASE_APPROVED'] = value
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script], env=env,
                                    text=True, capture_output=True, timeout=5)
            with self.subTest(value=value):
                self.assertEqual(value == 'true', result.returncode == 0, result.stderr)


class PublishWorkflowTest(unittest.TestCase):
    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            signed = root / 'signed-apks'
            scripts = root / 'scripts'
            bin_dir = root / 'bin'
            runner = root / 'runner'
            for directory in (signed, scripts, bin_dir, runner):
                directory.mkdir()
            for name in ('app-arm64-v8a-release.apk', 'app-armeabi-v7a-release.apk',
                         'app-universal-release.apk', 'app-x86_64-release.apk'):
                (signed / name).write_bytes(('signed ' + name).encode())
            shutil.copy(ROOT / 'scripts/verify-release-draft.py', scripts)
            # These are fake fixtures; the real signing and aapt checks run separately in CI.
            (scripts / 'verify-release-signing.sh').write_text('#!/bin/bash\nexit 0\n')
            (scripts / 'verify-release-metadata.py').write_text('import sys\nsys.exit(0)\n')
            gh = bin_dir / 'gh'
            gh.write_text(MOCK_GH)
            gh.chmod(0o755)
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                       RUNNER_TEMP=str(runner), GITHUB_REPOSITORY='rahmaniho/V2max',
                       GITHUB_SHA='a' * 40, VERSION='2.4.0', MOCK_SCENARIO=scenario)
            env.pop('GITHUB_OUTPUT', None)
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', publication_script()],
                                    cwd=root, env=env, text=True, capture_output=True, timeout=30)
            state_file = runner / 'mock-release.json'
            state = json.loads(state_file.read_text()) if state_file.exists() else None
            log_file = runner / 'mock-gh.log'
            calls = [json.loads(line) for line in log_file.read_text().splitlines()] if log_file.exists() else []
            universal_digest = 'sha256:' + hashlib.sha256((signed / 'app-universal-release.apk').read_bytes()).hexdigest()
            return result, state, calls, universal_digest

    def test_success_publishes_verified_universal_alias(self):
        result, release, calls, digest = self.run_scenario('success')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(release['draft'])
        self.assertEqual('00-V2max-universal.apk', release['assets'][0]['name'])
        self.assertEqual(digest, release['assets'][0]['digest'])
        self.assertTrue(any(call[:2] == ['release', 'edit'] for call in calls))
        self.assertIn('Published verified release v2.4.0', result.stdout)

    def test_bad_draft_or_moved_main_never_publishes(self):
        for scenario in ('bad-draft-order', 'bad-alias-digest', 'bad-list-order', 'main-moved'):
            with self.subTest(scenario=scenario):
                result, release, calls, _ = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertTrue(release['draft'])
                self.assertFalse(any(call[:2] == ['release', 'edit'] for call in calls))

    def test_wrong_published_response_is_not_reported_as_success(self):
        for scenario in ('bad-published-url', 'bad-published-list'):
            with self.subTest(scenario=scenario):
                result, release, calls, _ = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertFalse(release['draft'])
                self.assertTrue(any(call[:2] == ['release', 'edit'] for call in calls))
                self.assertNotIn('Published verified release', result.stdout)


if __name__ == '__main__':
    unittest.main()
