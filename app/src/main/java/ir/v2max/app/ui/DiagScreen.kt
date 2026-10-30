package ir.v2max.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.core.XrayCore
import ir.v2max.app.data.Repo
import ir.v2max.app.net.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * صفحه‌ی عیب‌یابی: نمایش زنده‌ی لاگ هسته، IP/کشور خروجی، بررسی DNS و خروجی گزارش.
 */
@Composable
fun DiagScreen(
    appVersion: String,
    coreVersion: String,
    onBack: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val tunnel by Repo.tunnel.collectAsStateWithLifecycle()
    val settings by Repo.settings.collectAsStateWithLifecycle()

    var logs by remember { mutableStateOf(XrayCore.logs()) }
    var exit by remember { mutableStateOf(Diagnostics.ExitInfo()) }
    var dns by remember { mutableStateOf(Diagnostics.DnsInfo()) }
    var busy by remember { mutableStateOf(false) }
    var lastCheck by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val listener: (String) -> Unit = { logs = XrayCore.logs() }
        XrayCore.addLogListener(listener)
        while (true) {
            delay(1500)
            logs = XrayCore.logs()
        }
    }

    fun runChecks() {
        busy = true
        scope.launch {
            exit = Diagnostics.exitInfo()
            dns = Diagnostics.dnsCheck(settings.remoteDns)
            lastCheck = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            busy = false
        }
    }

    fun shareReport() {
        val report = Diagnostics.buildReport(
            appVersion = appVersion,
            coreVersion = coreVersion,
            deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · اندروید ${android.os.Build.VERSION.RELEASE}",
            serverLabel = tunnel.serverName.ifBlank { "—" },
            tunnelStatus = tunnel.status.name,
            exit = exit,
            dns = dns,
            logs = logs
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "گزارش عیب‌یابی V2max")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "ارسال گزارش")) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(V2.Surface)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = V2.TextMuted, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("عیب‌یابی و گزارش", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
                    Text("وضعیت خروج، DNS و لاگ زنده‌ی هسته", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                }
            }
        }

        // ---- تشخیص خودکار با اقدام پیشنهادی
        val findings = remember(exit, dns, tunnel, settings) {
            val alive = Repo.servers.value.count { it.bestPing() > 0 || it.tcpPing > 0 }
            Diagnostics.advise(
                connected = tunnel.status == ir.v2max.app.data.TunnelStatus.CONNECTED,
                connecting = tunnel.status == ir.v2max.app.data.TunnelStatus.CONNECTING,
                killSwitchBlocked = settings.killSwitch && tunnel.status == ir.v2max.app.data.TunnelStatus.ERROR,
                exitOk = exit.ok,
                dnsDohOk = dns.dohOk,
                dnsConsistent = dns.consistent,
                totalServers = Repo.servers.value.size,
                aliveServers = alive,
                balancerEnabled = settings.balancer,
                geoReady = ir.v2max.app.core.GeoAssets.isReady(),
                fragmentProfile = settings.fragmentProfile.name
            )
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "تشخیص خودکار",
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    findings.forEach { finding ->
                        val color = when (finding.severity) {
                            Diagnostics.Severity.CRITICAL -> V2.Red
                            Diagnostics.Severity.WARN -> V2.Amber
                            Diagnostics.Severity.INFO -> V2.Sky
                            Diagnostics.Severity.OK -> V2.Mint
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 5.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(finding.title, fontSize = 12.sp, color = color)
                                Text(finding.detail, fontSize = 10.5.sp, color = V2.TextMuted)
                                if (finding.actionId.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(color.copy(alpha = 0.14f))
                                            .clickable { onAction(finding.actionId) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(finding.actionLabel, fontSize = 10.5.sp, color = color)
                                    }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("وضعیت خروج", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = V2.TextPrimary, modifier = Modifier.weight(1f))
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = V2.Mint)
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoLine("IP خروجی", exit.ip.ifBlank { if (busy) "در حال بررسی…" else "—" })
                    InfoLine("کشور", exit.country.ifBlank { "—" })
                    InfoLine("نود شبکه", exit.colo.ifBlank { "—" })
                    if (exit.error.isNotBlank()) InfoLine("خطا", exit.error)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(text = "بررسی مجدد", modifier = Modifier.weight(1f)) { runChecks() }
                        GhostButton(text = "ارسال گزارش", modifier = Modifier.weight(1f)) { shareReport() }
                    }
                    if (lastCheck.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("آخرین بررسی: $lastCheck", fontSize = 10.sp, color = V2.TextMuted)
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("DNS", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    InfoLine("DNS امن (DoH)", if (dns.dohOk) dns.dohAnswer else "بدون پاسخ")
                    InfoLine("DNS سیستم", dns.systemAnswer.ifBlank { "—" })
                    InfoLine("هم‌خوانی", if (dns.consistent) "یکسان" else "نامشخص / متفاوت")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "توجه: تشخیص قطعی نشت DNS از داخل اپلیکیشن ممکن نیست؛ این بخش فقط تأیید می‌کند که پاسخ DoH از مسیر تانل دریافت می‌شود.",
                        fontSize = 10.sp,
                        color = V2.TextMuted
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("لاگ زنده", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = V2.TextPrimary, modifier = Modifier.weight(1f))
                GhostButton(text = "پاک کردن", modifier = Modifier.width(96.dp)) { XrayCore.clearLogs(); logs = emptyList() }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .heightIn(min = 120.dp, max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text("هنوز رویدادی ثبت نشده است.", fontSize = 11.sp, color = V2.TextMuted)
                    } else {
                        logs.takeLast(300).forEach { line ->
                            Text(line, fontSize = 10.sp, color = V2.TextMuted, modifier = Modifier.padding(vertical = 1.dp))
                        }
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
