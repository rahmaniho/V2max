#!/usr/bin/env python3
"""Check both backup systems exclude device-local consent and private error logs."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
repo = (ROOT / "app/src/main/java/ir/v2max/app/data/Repo.kt").read_text(encoding="utf-8")
assert 'private const val CONTROL_PREF_NAME = "v2max_control_consent"' in repo
CONSENT = "v2max_control_consent.xml"

manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
app = manifest.find("application")
assert app is not None
assert app.get(ANDROID + "fullBackupContent") == "@xml/backup_rules"
assert app.get(ANDROID + "dataExtractionRules") == "@xml/data_extraction_rules"


def excludes(section, domain, path):
    return any(
        child.tag == "exclude" and child.get("domain") == domain and child.get("path") == path
        for child in section
    )


def verify_section(section, label):
    assert excludes(section, "sharedpref", CONSENT), f"missing consent exclusion for {label}"
    assert excludes(section, "file", "crash.log"), f"missing private crash-log exclusion for {label}"


legacy = ET.parse(RES / "backup_rules.xml").getroot()
modern = ET.parse(RES / "data_extraction_rules.xml").getroot()
assert legacy.tag == "full-backup-content"
verify_section(legacy, "legacy")
assert modern.tag == "data-extraction-rules"
for name in ("cloud-backup", "device-transfer"):
    section = modern.find(name)
    assert section is not None, f"missing {name}"
    verify_section(section, name)
print("Automation consent and local crash logs are excluded from legacy, cloud and device-transfer backup rules.")
