#!/usr/bin/env python3
"""Catch accidental weakening of the release workflow's explicit safety gates.

This checks the current workflow structure, not a defense against an attacker who
can modify both the workflow and this script. GitHub branch protection and owner
review are still required.
"""
import pathlib
import re
import sys

CONDITION = ("github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && "
             "inputs.publish_release && !inputs.security_only && !inputs.publish_test_release && "
             "!inputs.validate_preview && !inputs.ui_diagnostics && vars.V2MAX_RELEASE_APPROVED == 'true'")
PREVIEW_CONDITION = ("github.event_name == 'workflow_dispatch' && startsWith(github.ref, 'refs/heads/arena/') && "
                     "inputs.publish_test_release && !inputs.security_only && !inputs.publish_release && !inputs.validate_preview && !inputs.ui_diagnostics")
PREVIEW_TEST_CONDITION = ("github.event_name == 'workflow_dispatch' && startsWith(github.ref, 'refs/heads/arena/') && "
                          "(inputs.publish_test_release || inputs.validate_preview) && !inputs.security_only && !inputs.publish_release && !inputs.ui_diagnostics")


def section(source, name):
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", source)
    if not match:
        raise ValueError(f"Workflow job missing: {name}")
    return match.group(1)


def verify(source):
    input_match = re.search(r"(?ms)^      publish_release:\n(.*?)(?=^      [a-z_]+:\n|^env:\n)", source)
    if not input_match or not re.search(r"(?m)^        default: false\s*$", input_match.group(1)):
        raise ValueError("Manual publication must default to false")
    for name in ("sign-release", "publish"):
        body = section(source, name)
        condition = re.search(r"(?m)^    if: (.+)$", body)
        if not condition or " ".join(condition.group(1).split()) != CONDITION:
            raise ValueError(f"{name} no longer requires explicit main dispatch and owner approval")
    if not re.search(r"(?m)^    needs: \[build, security-smoke\]\s*$", section(source, "sign-release")):
        raise ValueError("Signing must depend on both the full build and security matrix")
    if not re.search(r"(?m)^    needs: sign-release\s*$", section(source, "publish")):
        raise ValueError("Publication must depend on signing")
    security = section(source, "security-smoke")
    if "inputs.publish_release" not in security or "inputs.security_only" not in security:
        raise ValueError("Release dispatch must run the security matrix")
    for name in ("build", "security-smoke"):
        if re.search(r"secrets\.V2MAX_RELEASE_", section(source, name)):
            raise ValueError(f"Production signing secrets are exposed to {name}")
    signing = section(source, "sign-release")
    publication = section(source, "publish")
    if not re.search(r"(?m)^      contents: read\s*$", signing):
        raise ValueError("Signing job must not have repository write access")
    if re.search(r"secrets\.V2MAX_RELEASE_", publication):
        raise ValueError("Publication job must not receive the private signing key")
    draft_lookup = 'draft_api=$(gh release view "$TAG" --json apiUrl --jq .apiUrl)'
    if draft_lookup not in publication or 'gh api "$draft_api"' not in publication:
        raise ValueError("Stable draft must be resolved by release ID before verification")
    # v2.3.1 chooses the first APK; GitHub can order assets by name. The
    # early-sorting alias must come from the signed universal APK and the draft
    # verifier must check the actual API order and all digests before publishing.
    release_steps = (
        'cp signed-apks/app-universal-release.apk "$RUNNER_TEMP/legacy-update/00-V2max-universal.apk"',
        'gh release create "$TAG" "$RUNNER_TEMP/legacy-update/00-V2max-universal.apk" signed-apks/*.apk --verify-tag --draft',
        'python3 scripts/verify-release-draft.py "$RUNNER_TEMP/draft.json" signed-apks "$TAG"',
        'python3 scripts/verify-release-draft.py "$RUNNER_TEMP/draft-list.json" signed-apks "$TAG"',
        'gh release edit "$TAG" --draft=false --latest',
        'python3 scripts/verify-release-draft.py "$RUNNER_TEMP/published-latest.json" signed-apks "$TAG" published',
        'python3 scripts/verify-release-draft.py "$RUNNER_TEMP/published-list.json" signed-apks "$TAG" published',
    )
    positions = [publication.find(step) for step in release_steps]
    if -1 in positions or positions != sorted(positions) or len(set(positions)) != len(positions):
        raise ValueError("Stable release must verify the universal alias and draft before publishing")

    for input_name in ("publish_test_release", "validate_preview", "ui_diagnostics"):
        preview_input = re.search(rf"(?ms)^      {input_name}:\n(.*?)(?=^      [a-z_]+:\n|^env:\n)", source)
        if not preview_input or not re.search(r"(?m)^        default: false\s*$", preview_input.group(1)):
            raise ValueError(f"{input_name} must default to false")
    preview = section(source, "publish-preview")
    condition = re.search(r"(?m)^    if: (.+)$", preview)
    if not condition or " ".join(condition.group(1).split()) != PREVIEW_CONDITION:
        raise ValueError("Preview publication must require a separate explicit branch dispatch")
    if not re.search(r"(?m)^    needs: \[build, security-smoke\]\s*$", preview):
        raise ValueError("Preview publication must require the build and security matrix")
    if "(inputs.publish_test_release || inputs.validate_preview)" not in security:
        raise ValueError("Both preview dispatch modes must run the security matrix")
    if re.search(r"secrets\.V2MAX_RELEASE_", preview):
        raise ValueError("Preview publication must not receive production signing secrets")
    if preview.count("--prerelease --latest=false") < 2 or "scripts/verify-preview-release.py" not in preview:
        raise ValueError("Preview must be verified, marked prerelease and never marked latest")
    if draft_lookup not in preview or 'gh api "$draft_api"' not in preview:
        raise ValueError("Preview draft must be resolved by release ID before verification")
    build = section(source, "build")
    for name in ("Build isolated preview and its instrumentation APK",
                 "Stage the exact preview APK that passed the TUN test",
                 "Upload isolated tested preview APK"):
        step = re.search(rf"(?ms)^      - name: {re.escape(name)}\n(.*?)(?=^      - name: |\Z)", build)
        condition = re.search(r"(?m)^        if: (.+)$", step.group(1)) if step else None
        if not condition or " ".join(condition.group(1).split()) != PREVIEW_TEST_CONDITION:
            raise ValueError(f"{name} must require explicit preview validation or publication")
    preview_env = "V2MAX_PREVIEW_SMOKE: ${{ " + PREVIEW_TEST_CONDITION + " && 'true' || 'false' }}"
    if preview_env not in build or 'sha256sum --check "$RUNNER_TEMP/v2max-preview-tested.sha256"' not in build:
        raise ValueError("The preview must pass the emulator TUN test before staging")
    if "v2max-preview-tested-apk" not in build or "v2max-preview-tested-apk" not in preview:
        raise ValueError("Preview publication must use the tested preview artifact")
    print("Explicit stable/preview dispatch, approval and job separation verified.")


if __name__ == "__main__":
    try:
        verify(pathlib.Path(".github/workflows/build.yml").read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        sys.exit(f"::error title=release gates::{error}")
