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
             "inputs.publish_release && !inputs.security_only && vars.V2MAX_RELEASE_APPROVED == 'true'")


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
    print("Explicit release dispatch, approval and job separation verified.")


if __name__ == "__main__":
    try:
        verify(pathlib.Path(".github/workflows/build.yml").read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        sys.exit(f"::error title=release gates::{error}")
