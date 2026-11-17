#!/usr/bin/env python3
"""Report only fixed error categories from a failed signing job, never its logs."""
import json
import os
import re
import subprocess
import sys

SECRET_NAMES = {
    "KEYSTORE_BASE64": "V2MAX_RELEASE_KEYSTORE_BASE64",
    "V2MAX_RELEASE_STORE_PASSWORD": "V2MAX_RELEASE_STORE_PASSWORD",
    "V2MAX_RELEASE_KEY_ALIAS": "V2MAX_RELEASE_KEY_ALIAS",
    "V2MAX_RELEASE_KEY_PASSWORD": "V2MAX_RELEASE_KEY_PASSWORD",
}
PATTERNS = (
    ("invalid base64 encoding", r"(?i)base64:\s*invalid input"),
    ("invalid keystore format", r"(?i)(invalid keystore format|unrecognized keystore format|not a valid keystore)"),
    ("keystore password rejected", r"(?i)(keystore password was incorrect|keystore was tampered with, or password was incorrect)"),
    ("private key could not be recovered", r"(?i)(UnrecoverableKeyException|cannot recover key|cannot recover private key)"),
    ("key alias not found", r"(?i)(no key with alias|does not contain key|alias [^\n]* does not exist)"),
    ("signer could not be loaded", r"(?i)failed to load signer"),
    ("signing or verification failed", r"(?i)(failed to sign|failed to verify|signer mismatch)"),
    ("Android signing tool missing", r"(?i)(Android apksigner was not found|Android zipalign was not found)"),
    ("runner disk full", r"(?i)no space left on device"),
)


def categories(log):
    result = []
    missing = re.findall(r"Required signing secret\s+(KEYSTORE_BASE64|V2MAX_RELEASE_STORE_PASSWORD|V2MAX_RELEASE_KEY_ALIAS|V2MAX_RELEASE_KEY_PASSWORD)\s+is not configured", log)
    result.extend("missing secret: " + SECRET_NAMES[name] for name in sorted(set(missing)))
    result.extend(label for label, pattern in PATTERNS if re.search(pattern, log))
    return result or ["No recognized cause in the available failed-step log."]


def run(args):
    # Never emit subprocess output on error: it can contain signed URLs or logs.
    result = subprocess.run(args, text=True, capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError("Could not read the job metadata or failed-step log")
    return result.stdout


def main():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    run_id = os.environ.get("DIAGNOSTIC_RUN", "")
    job_id = os.environ.get("DIAGNOSTIC_JOB", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not run_id.isdecimal() or not job_id.isdecimal():
        raise ValueError("Expected a repository and numeric run/job IDs")
    job = json.loads(run(["gh", "api", f"repos/{repo}/actions/jobs/{job_id}"]))
    if job.get("name") != "sign-release" or str(job.get("run_id")) != run_id or job.get("conclusion") != "failure":
        raise ValueError("The selected job is not a failed sign-release job in this run")
    log = run(["gh", "run", "view", run_id, "--repo", repo, "--job", job_id, "--log-failed"])
    for result in categories(log):
        print(f"::notice title=Signing diagnosis::{result}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        # Exception messages are deliberately fixed and must never contain URLs or raw logs.
        print("::error title=Signing diagnosis::Signing-log classification could not be completed.", file=sys.stderr)
        sys.exit(1)
