#!/usr/bin/env python3
"""Fail-closed source guards for the local-only diagnostics surface.

Behavior and exact-preview sharing are also covered by Kotlin and emulator tests;
these guards prevent the old direct-IP/DNS request path from silently returning.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/java/ir/v2max/app"
DOMAIN = ROOT / "core/domain/src/main/kotlin/ir/v2max/core/domain"


def read(path):
    return path.read_text(encoding="utf-8")


class DiagnosticPrivacyGuards(unittest.TestCase):
    def test_old_direct_network_diagnostics_are_absent(self):
        self.assertFalse((APP / "net/Diagnostics.kt").exists())
        for path in (APP / "net/LocalDiagnostics.kt", APP / "ui/DiagScreen.kt", APP / "ui/ReportPreviewDialog.kt"):
            source = read(path)
            for forbidden in ("okhttp", "HttpURLConnection", "InetAddress", "cdn-cgi/trace", "dns-query", "newCall("):
                self.assertNotIn(forbidden, source, f"{path}: unexpected direct diagnostic network path")
        screen = read(APP / "ui/DiagScreen.kt")
        self.assertIn('InfoLine("IP خروجی VPN", "نامشخص")', screen)
        self.assertIn('InfoLine("DNS و نشت DNS", "نامشخص")', screen)

    def test_reports_have_no_raw_server_dns_or_log_inputs(self):
        source = read(DOMAIN / "PrivateDiagnosticReport.kt")
        snapshot = source.split("data class Snapshot(", 1)[1].split(")", 1)[0]
        for forbidden in ("serverLabel", "serverName", "endpoint", "ip:", "dns:", "logs:", "throwable"):
            self.assertNotIn(forbidden, snapshot)
        self.assertIn('عبور ترافیک برنامه‌های دیگر از TUN: تأیید نشده', source)
        self.assertIn('IP خروجی VPN: نامشخص', source)

    def test_sharing_and_copy_are_driven_by_the_preview_text(self):
        screen = read(APP / "ui/DiagScreen.kt")
        preview = screen.split("preview?.let", 1)[1].split("LazyColumn(", 1)[0]
        self.assertLess(preview.index("onConfirm = { text ->"), preview.index("Intent(Intent.ACTION_SEND)"))
        self.assertIn("putExtra(Intent.EXTRA_TEXT, text)", preview)
        root = read(APP / "ui/AppRoot.kt")
        crash_preview = root.split("crashReportPreview?.let", 1)[1].split("if (qrDialogContent", 1)[0]
        self.assertIn("onConfirm = { text ->", crash_preview)
        self.assertIn('newPlainText("V2max private report", text)', crash_preview)
        self.assertNotIn("CrashLog.last()", root)

    def test_new_error_records_and_core_events_do_not_forward_raw_messages(self):
        crash = read(APP / "util/CrashLog.kt")
        core = read(APP / "core/XrayCore.kt")
        for forbidden in ("throwable.message", "stackTraceToString", "Log.e(TAG, \"crash[", "fun last(): String"):
            self.assertNotIn(forbidden, crash)
        self.assertIn("PrivateErrorRecord.classify(throwable, tag)", crash)
        self.assertIn("PrivateErrorRecord.decode(firstLine) ?: PrivateErrorRecord.unknown", crash)
        self.assertIn("fun log(event: Event)", core)
        self.assertNotIn('Log.i(TAG, "core: $message")', core)
        self.assertNotIn("log(message)", core)

    def test_canceled_scan_does_not_leave_a_completed_summary(self):
        smart = read(APP / "core/SmartConnect.kt")
        self.assertGreaterEqual(smart.count('diagnosticCounts = null, message = "تست لغو شد"'), 2)

    def test_test_button_does_not_autostart_a_vpn(self):
        screen = read(APP / "ui/DiagScreen.kt")
        root = read(APP / "ui/AppRoot.kt")
        vm = read(APP / "ui/AppViewModel.kt")
        self.assertIn("if (!scan.running) onAction(ConnectionAdvice.Action.TEST_SERVERS)", screen)
        self.assertIn("ConnectionAdvice.Action.TEST_SERVERS -> vm.testAll()", root)
        test_all = vm.split("fun testAll()", 1)[1].split("fun testServer(", 1)[0]
        self.assertIn("autoConnect = false", test_all)
        self.assertIn("exhaustive = true", test_all)


if __name__ == "__main__":
    unittest.main()
