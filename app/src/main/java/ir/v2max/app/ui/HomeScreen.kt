package ir.v2max.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.NetworkAssessment
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.SyncState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.data.UpdateInfo
import ir.v2max.app.util.Format
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    servers: List<ServerConfig>,
    selectedId: String,
    tunnel: TunnelUiState,
    scan: ScanState,
    sync: SyncState,
    network: NetworkAssessment,
    update: UpdateInfo,
    settings: AppSettings,
    onToggle: () -> Unit,
    onSmart: () -> Unit,
    onOpenSubs: () -> Unit,
    onOpenServers: () -> Unit,
    onRestart: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onInstallUpdate: () -> Unit = {},
    killSwitchBlocked: Boolean = false,
    onUnblock: () -> Unit = {},
    latencySamples: List<Int> = emptyList(),
    poolSize: Int = 0,
    updateProgress: Float = 0f,
    coreVersion: String
) {
    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    val displayStatus = if (scan.running && tunnel.status != TunnelStatus.CONNECTED) {
        TunnelStatus.CONNECTING
    } else tunnel.status

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // تیک ثانیه‌ای فقط وقتی اتصال برقرار است (به‌جای بازسازی مداوم صفحه در حالت قطع)
    LaunchedEffect(tunnel.status, tunnel.connectedAt) {
        while (tunnel.status == TunnelStatus.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val connectedFor = if (tunnel.status == TunnelStatus.CONNECTED && tunnel.connectedAt > 0) {
        now - tunnel.connectedAt
    } else 0L

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            HeaderRow(tunnel)
        }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                ConnectOrb(displayStatus, onToggle)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (scan.running) "در حال بررسی هوشمند" else statusTitle(tunnel),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor(displayStatus)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        scan.running -> "تست پیش از اتصال؛ برای لغو دکمه را لمس کنید"
                        tunnel.message.isNotBlank() && tunnel.status == TunnelStatus.CONNECTING -> tunnel.message
                        else -> tunnel.serverName.ifBlank { selected?.displayName() ?: "سروری انتخاب نشده" }
                    },
                    fontSize = 12.5.sp,
                    color = V2.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (killSwitchBlocked) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "اینترنت مسدود است (Kill Switch)",
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            color = V2.Red
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "تانل قطع شده و برای محافظت از حریم خصوصی، ترافیک مسدود مانده است.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = V2.TextMuted
                        )
                        Spacer(Modifier.height(10.dp))
                        GhostButton(text = "رفع مسدودی و بازگشت اینترنت", modifier = Modifier.fillMaxWidth()) {
                            onUnblock()
                        }
                    }
                }
            }
        }

        if (update.hasUpdate) {
            item {
                UpdateBanner(
                    version = update.version,
                    onOpen = onOpenUpdate,
                    onDismiss = onDismissUpdate,
                    onInstall = onInstallUpdate,
                    progress = updateProgress
                )
            }
        }

        // جزئیات فقط وقتی اتصال برقرار است نمایش داده می‌شوند تا صفحه‌ی اصلی ساده بماند
        if (tunnel.status == TunnelStatus.CONNECTED) {
            item {
                LatencyCard(
                    samples = latencySamples,
                    poolSize = poolSize,
                    connected = true
                )
            }
        }

        item { NetworkCard(network, onRefreshNetwork) }

        item { SyncBanner(sync) }

        item { ScanBanner(scan) }

        if (tunnel.status == TunnelStatus.CONNECTED) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "دریافت",
                        value = Format.speed(tunnel.downSpeed),
                        accent = V2.Mint,
                        arrow = "↓"
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "ارسال",
                        value = Format.speed(tunnel.upSpeed),
                        accent = V2.Sky,
                        arrow = "↑"
                    )
                }
            }
        }

        if (tunnel.status == TunnelStatus.CONNECTED) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MiniStat("مدت اتصال", Format.duration(connectedFor))
                        Divider()
                        MiniStat(
                            "پینگ",
                            if (tunnel.ping > 0) "${Format.number(tunnel.ping)} ms" else "—"
                        )
                        Divider()
                        MiniStat(
                            "مصرف",
                            Format.bytes(tunnel.totalDown + tunnel.totalUp)
                        )
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth(), onClick = onOpenServers) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(V2.Mint.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (selected?.proto?.label ?: "V2").take(4),
                            color = V2.Mint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected?.displayName() ?: "سروری انتخاب نشده",
                            style = MaterialTheme.typography.titleSmall,
                            color = V2.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            selected?.let { "${it.address}:${it.port}" } ?: "برای انتخاب سرور ضربه بزنید",
                            style = MaterialTheme.typography.bodySmall,
                            color = V2.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected != null && selected.reachCount() > 0) {
                            Spacer(Modifier.height(5.dp))
                            PingTargetsRow(
                                selected.googlePing,
                                selected.instagramPing,
                                selected.youtubePing
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // A TCP handshake alone is not a successful VPN test.
                    PingBadge(selected?.realPing ?: -1)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = when {
                        scan.running -> "در حال اسکن…"
                        tunnel.status == TunnelStatus.CONNECTED -> "تغییر به سریع‌ترین"
                        else -> "اتصال هوشمند"
                    },
                    modifier = Modifier.weight(1f),
                    loading = scan.running,
                    enabled = servers.isNotEmpty() && !scan.running,
                    onClick = onSmart
                )
                GhostButton(
                    text = "سرورها",
                    modifier = Modifier.width(104.dp),
                    onClick = onOpenServers
                )
            }
        }

        item {
            // ری‌استارت: پاک کردن نتایج قبلی و اجرای دوباره‌ی کل چرخه‌ی جستجو
            RestartButton(
                running = scan.running,
                enabled = servers.isNotEmpty(),
                onClick = onRestart
            )
        }

        if (servers.isEmpty()) {
            item {
                EmptyState(
                    title = "هنوز هیچ سروری ندارید",
                    description = "لینک اشتراک خود را اضافه کنید؛ V2max به‌صورت خودکار سالم‌ترین و سریع‌ترین سرور را انتخاب و وصل می‌شود.",
                    actionLabel = "افزودن اشتراک",
                    onAction = onOpenSubs
                )
            }
        }

        item {
            Text(
                text = "هسته: $coreVersion  •  حالت مسیریابی: " +
                    if (settings.routingMode == ir.v2max.app.data.RoutingMode.SMART) "هوشمند (ایران مستقیم)" else "همه از تانل",
                color = V2.TextMuted,
                fontSize = 10.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun RestartButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(V2.Surface.copy(alpha = 0.65f))
            .border(1.dp, if (running) V2.Amber.copy(alpha = 0.5f) else V2.Stroke, shape)
            .clickable(enabled = enabled && !running) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = V2.Amber,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Text("⟳", color = V2.Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (running) "در حال جستجوی دوباره…" else "ری‌استارت و جستجوی دوباره‌ی سرورها",
            color = if (running) V2.Amber else V2.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HeaderRow(tunnel: TunnelUiState) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "V2max",
                    style = MaterialTheme.typography.headlineMedium,
                    color = V2.TextPrimary
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(V2.Mint)
                )
            }
            Text(
                "اینترنت آزاد، سریع و امن",
                style = MaterialTheme.typography.bodySmall,
                color = V2.TextMuted
            )
        }
        when (tunnel.status) {
            TunnelStatus.CONNECTED -> StatusPill("متصل", V2.Mint)
            TunnelStatus.CONNECTING -> StatusPill("در حال اتصال", V2.Amber)
            TunnelStatus.DISCONNECTING -> StatusPill("در حال قطع", V2.Amber)
            TunnelStatus.ERROR -> StatusPill("خطا", V2.Red)
            else -> StatusPill("قطع", V2.TextMuted)
        }
    }
}

/**
 * دکمه‌ی اصلی اتصال — نسخه‌ی ساده و سبک.
 *
 * نسخه‌ی قبلی سه انیمیشن بی‌پایان (پالس، چرخش، تنفس) روی یک Canvas ۲۶۸dp با
 * گرادیان‌های شعاعی داشت که هر فریم دوباره رسم می‌شد؛ روی گوشی‌های ضعیف باعث
 * کندی، داغ شدن و حتی بسته‌شدن برنامه می‌شد. حالا فقط یک چرخش ساده هنگام
 * اتصال داریم و بقیه‌ی حالت‌ها ثابت‌اند.
 */
@Composable
private fun ConnectOrb(status: TunnelStatus, onClick: () -> Unit) {
    val accent = statusColor(status)
    val busy = status == TunnelStatus.CONNECTING || status == TunnelStatus.DISCONNECTING
    val spin = if (busy) {
        val transition = rememberInfiniteTransition(label = "spin")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "spinAngle"
        )
        value
    } else {
        0f
    }

    Canvas(
        modifier = Modifier
            .size(210.dp)
            .clickable { onClick() }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f - 4.dp.toPx()

        // حلقه‌ی پس‌زمینه
        drawCircle(
            color = V2.SurfaceHigh,
            radius = maxRadius,
            center = center
        )
        drawCircle(
            color = accent.copy(alpha = 0.55f),
            radius = maxRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // حلقه‌ی وضعیت: هنگام کار می‌چرخد، در بقیه‌ی حالت‌ها ثابت است
        drawArc(
            color = accent,
            startAngle = -90f + spin,
            sweepAngle = if (busy) 80f else 360f,
            useCenter = false,
            topLeft = Offset(center.x - maxRadius + 6.dp.toPx(), center.y - maxRadius + 6.dp.toPx()),
            size = Size((maxRadius - 6.dp.toPx()) * 2f, (maxRadius - 6.dp.toPx()) * 2f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // نماد روشن/خاموش
        val r = maxRadius * 0.22f
        drawArc(
            color = accent,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r + 3.dp.toPx()),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        )
        drawLine(
            color = accent,
            start = Offset(center.x, center.y - r * 1.55f + 3.dp.toPx()),
            end = Offset(center.x, center.y - r * 0.30f + 3.dp.toPx()),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accent: Color,
    arrow: String
) {
    GlassCard(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(arrow, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 10.5.sp, color = V2.TextMuted)
                Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2.TextPrimary)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = V2.TextPrimary)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = V2.TextMuted)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .height(28.dp)
            .width(1.dp)
            .background(V2.Stroke)
    )
}

private fun statusColor(status: TunnelStatus): Color = when (status) {
    TunnelStatus.CONNECTED -> V2.Mint
    TunnelStatus.CONNECTING, TunnelStatus.DISCONNECTING -> V2.Amber
    TunnelStatus.ERROR -> V2.Red
    else -> Color(0xFF40566F)
}

private fun statusTitle(tunnel: TunnelUiState): String = when (tunnel.status) {
    TunnelStatus.CONNECTED -> "اتصال برقرار است"
    TunnelStatus.CONNECTING -> "در حال اتصال…"
    TunnelStatus.DISCONNECTING -> "در حال قطع اتصال…"
    TunnelStatus.ERROR -> tunnel.message.ifBlank { "اتصال ناموفق بود" }
    else -> "برای اتصال ضربه بزنید"
}
