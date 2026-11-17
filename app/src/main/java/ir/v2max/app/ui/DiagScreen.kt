package ir.v2max.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.v2max.app.core.GeoAssets
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.Repo
import ir.v2max.app.net.LocalDiagnostics
import ir.v2max.app.util.CrashLog
import ir.v2max.core.domain.ConnectionAdvice
import kotlinx.coroutines.launch

/** Only locally known information; this page never probes IP or DNS directly. */
@Composable
fun DiagScreen(
    appVersion: String,
    coreVersion: String,
    onBack: () -> Unit,
    onAction: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val tunnel by Repo.tunnel.collectAsStateWithLifecycle()
    val settings by Repo.settings.collectAsStateWithLifecycle()
    val servers by Repo.servers.collectAsStateWithLifecycle()
    val scan by Repo.scan.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(XrayCore.logs()) }
    var preview by remember { mutableStateOf<String?>(null) }
    var shareError by remember { mutableStateOf(false) }
    val geoReady by GeoAssets.diagnosticReadiness.collectAsStateWithLifecycle()
    val observation = LocalDiagnostics.observation(tunnel, settings, servers, geoReady)
    val findings = ConnectionAdvice.advise(observation)

    DisposableEffect(Unit) {
        val listener: (String) -> Unit = { scope.launch { logs = XrayCore.logs() } }
        XrayCore.addLogListener(listener)
        onDispose { XrayCore.removeLogListener(listener) }
    }

    preview?.let { report ->
        ReportPreviewDialog(
            report = report,
            confirmLabel = "اشتراک‌گذاری",
            onDismiss = { preview = null },
            onConfirm = { text ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "گزارش خصوصی V2max")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                shareError = runCatching {
                    context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری گزارش"))
                }.isFailure
                preview = null
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(V2.Surface).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = V2.TextMuted, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("عیب‌یابی و گزارش", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
                    Text("وضعیت محلی و گزارش خصوصی", style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("وضعیت برنامه", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    findings.forEach { finding ->
                        val color = when (finding.severity) {
                            ConnectionAdvice.Severity.CRITICAL -> V2.Red
                            ConnectionAdvice.Severity.WARN -> V2.Amber
                            ConnectionAdvice.Severity.INFO -> V2.Sky
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(finding.title, fontSize = 12.sp, color = color)
                                Text(finding.detail, fontSize = 10.5.sp, color = V2.TextMuted)
                                if (finding.actionId.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp))
                                            .background(color.copy(alpha = 0.14f))
                                            .clickable { onAction(finding.actionId) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) { Text(finding.actionLabel, fontSize = 10.5.sp, color = color) }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("مسیر خروج و DNS", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    InfoLine("IP خروجی VPN", "نامشخص")
                    InfoLine("DNS و نشت DNS", "نامشخص")
                    Text(
                        "این برنامه از VPN خودش مستثناست؛ درخواست از داخل آن ممکن است IP واقعی را آشکار کند. " +
                            "برای همین اینجا درخواست IP یا DNS ارسال نمی‌شود و عبور از TUN تأیید نشده است.",
                        fontSize = 11.sp, color = V2.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "برای بررسی مسیر خروج، از برنامهٔ دیگری که مشمول VPN و قواعد مسیریابی آن است استفاده کنید. " +
                            "این کار به‌تنهایی نبود نشت DNS را ثابت نمی‌کند.",
                        fontSize = 11.sp, color = V2.TextMuted,
                    )
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("آخرین اسکن انتخاب یا گروهی", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    val counts = if (scan.finished && !scan.running) scan.diagnosticCounts else null
                    if (counts == null) {
                        Text(if (scan.running) "اسکن در حال انجام است؛ نتیجه هنوز نامشخص است." else
                            "اسکن کامل در این اجرای برنامه ثبت نشده است؛ نتیجه نامشخص است.",
                            fontSize = 11.sp, color = V2.TextMuted)
                    } else {
                        InfoLine("پاسخ‌گو", counts.responded.toString())
                        InfoLine("بی‌پاسخ", counts.noResponse.toString())
                        InfoLine("نامشخص", counts.unknown.toString())
                        Text(
                            "این فقط نتیجهٔ همان اسکن است و ممکن است قدیمی باشد؛ بی‌پاسخ به معنی خرابی دائمی نیست. " +
                                "موارد تست‌نشده و خطای نامشخص در دستهٔ نامشخص‌اند؛ TCP به‌تنهایی سلامت پروکسی نیست.",
                            fontSize = 11.sp, color = V2.TextMuted,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton(text = if (scan.running) "در حال تست سرورها" else "تست همهٔ سرورها", modifier = Modifier.fillMaxWidth()) {
                        if (!scan.running) onAction(ConnectionAdvice.Action.TEST_SERVERS)
                    }
                    Text("این تست اتصال جدیدی شروع نمی‌کند، اما به سرورها درخواست می‌فرستد و آنها ممکن است IP شبکه را ببینند. " +
                        "اگر بررسی عمیق فعال باشد، نمونه‌های DNS و TCP/TLS نیز مستقیماً سنجیده می‌شوند.",
                        fontSize = 10.sp, color = V2.TextMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("گزارش خصوصی", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Text("نام سرور، IP، نشانی‌ها، لینک‌ها و متن خام خطا یا لاگ در گزارش نیستند.",
                        fontSize = 11.sp, color = V2.TextMuted, modifier = Modifier.padding(vertical = 8.dp))
                    GhostButton(text = "پیش‌نمایش و اشتراک‌گذاری", modifier = Modifier.fillMaxWidth()) {
                        shareError = false
                        preview = LocalDiagnostics.report(
                            appVersion, coreVersion, android.os.Build.VERSION.SDK_INT,
                            tunnel, settings, servers, scan, geoReady, CrashLog.latestSummary(),
                        )
                    }
                    if (shareError) Text("باز کردن اشتراک‌گذاری ممکن نشد؛ دوباره تلاش کنید.", color = V2.Amber, fontSize = 11.sp)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("رویدادهای محلی", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary, modifier = Modifier.weight(1f))
                GhostButton(text = "پاک کردن", modifier = Modifier.width(110.dp)) { XrayCore.clearLogs(); logs = emptyList() }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(min = 100.dp, max = 300.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("این رویدادها در گزارش اشتراکی قرار نمی‌گیرند.", fontSize = 10.sp, color = V2.TextMuted)
                    if (logs.isEmpty()) Text("هنوز رویدادی ثبت نشده است.", fontSize = 11.sp, color = V2.TextMuted)
                    else logs.takeLast(100).forEach { line ->
                        Text(line, fontSize = 10.sp, color = V2.TextMuted, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 12.sp, color = V2.TextMuted, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 12.sp, color = V2.TextPrimary)
    }
}
