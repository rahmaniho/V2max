#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("gates", pathlib.Path(__file__).with_name("verify-release-gates.py"))
gates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gates)


class GatesTest(unittest.TestCase):
    def setUp(self):
        self.source = pathlib.Path(".github/workflows/build.yml").read_text(encoding="utf-8")

    def test_current_workflow_has_explicit_gates(self):
        gates.verify(self.source)

    def test_weakening_any_gate_is_rejected(self):
        changes = (
            (gates.CONDITION, gates.CONDITION.replace("workflow_dispatch", "push")),
            (gates.CONDITION, gates.CONDITION.replace(" && inputs.publish_release", "")),
            (gates.CONDITION, gates.CONDITION + " || true"),
            ("    needs: [build, security-smoke, release-approval]", "    needs: [build, security-smoke]"),
            (gates.RELEASE_REQUEST, gates.RELEASE_REQUEST.replace("workflow_dispatch", "push")),
            ("RELEASE_APPROVED: ${{ vars.V2MAX_RELEASE_APPROVED }}", "RELEASE_APPROVED: true"),
            ('if [[ "${RELEASE_APPROVED:-}" != true ]]; then', 'if false; then'),
            ("    needs: sign-release", "    needs: build"),
            ("      publish_release:\n", "      publish_release_missing:\n"),
            ("  build:\n", "  build:\n    env:\n      TEST: ${{ secrets.V2MAX_RELEASE_KEY_PASSWORD }}\n"),
            (gates.PREVIEW_CONDITION, gates.PREVIEW_CONDITION.replace("workflow_dispatch", "push")),
            (gates.PREVIEW_CONDITION, gates.PREVIEW_CONDITION.replace(" && !inputs.validate_preview", "")),
            (gates.PREVIEW_CONDITION, gates.PREVIEW_CONDITION.replace(" && !inputs.ui_diagnostics", "")),
            (gates.PREVIEW_TEST_CONDITION, gates.PREVIEW_TEST_CONDITION.replace("workflow_dispatch", "push")),
            ("      publish_test_release:\n", "      publish_test_release_missing:\n"),
            ("      validate_preview:\n", "      validate_preview_missing:\n"),
            ("      ui_diagnostics:\n", "      ui_diagnostics_missing:\n"),
            ("v2max-preview-tested-apk", "untested-preview-apk"),
            ('sha256sum --check "$RUNNER_TEMP/v2max-preview-tested.sha256"', "echo 'skip checksum'"),
            ("--prerelease --latest=false", "--latest"),
            ('draft_api=$(gh release view "$TAG" --json apiUrl --jq .apiUrl)', 'draft_api=invalid'),
            ('cp signed-apks/app-universal-release.apk "$RUNNER_TEMP/legacy-update/00-V2max-universal.apk"',
             'cp signed-apks/app-arm64-v8a-release.apk "$RUNNER_TEMP/legacy-update/00-V2max-universal.apk"'),
            ('gh release create "$TAG" "$RUNNER_TEMP/legacy-update/00-V2max-universal.apk" signed-apks/*.apk --verify-tag --draft',
             'gh release create "$TAG" signed-apks/*.apk --verify-tag --draft'),
            ('python3 scripts/verify-release-draft.py "$RUNNER_TEMP/draft.json" signed-apks "$TAG"',
             'echo skip-draft-verification'),
            ('python3 scripts/verify-release-draft.py "$RUNNER_TEMP/draft-list.json" signed-apks "$TAG"',
             'echo skip-list-verification'),
            ('gh release edit "$TAG" --draft=false --latest', 'echo skip-publishing'),
            ('python3 scripts/verify-release-draft.py "$RUNNER_TEMP/published-latest.json" signed-apks "$TAG" published',
             'echo skip-latest-verification'),
            ('python3 scripts/verify-release-draft.py "$RUNNER_TEMP/published-list.json" signed-apks "$TAG" published',
             'echo skip-published-list-verification'),
        )
        for before, after in changes:
            with self.subTest(before=before, after=after):
                self.assertIn(before, self.source)
                with self.assertRaises(ValueError):
                    gates.verify(self.source.replace(before, after, 1))


if __name__ == "__main__":
    unittest.main()
