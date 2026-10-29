package ir.v2max.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.NetworkAssessment
import ir.v2max.app.data.SyncState
import ir.v2max.app.util.Format

/** پس‌زمینه‌ی گرادیانی برنامه */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF081120), V2.Background, V2.BackgroundDeep)
                )
            )
    ) {
        // هاله‌ی نورانی بالای صفحه
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(V2.Mint.copy(alpha = 0.10f), Color.Transparent),
                        radius = 620f
                    )
                )
        )
        content()
    }
}

/** کارت شیشه‌ای (Glassmorphism) */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(V2.Surface.copy(alpha = 0.96f), Color(0xFF0A121D))
                )
            )
            .border(1.dp, V2.Stroke.copy(alpha = 0.85f), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        content()
    }
}

/** برچسب پینگ با رنگ‌بندی هوشمند */
@Composable
fun PingBadge(ping: Int, compact: Boolean = false) {
    val (color, label) = when {
        ping <= 0 -> V2.TextMuted to "—"
        ping < 200 -> V2.Mint to "${Format.number(ping)} ms"
        ping < 500 -> V2.Amber to "${Format.number(ping)} ms"
        else -> V2.Red to "${Format.number(ping)} ms"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(if (compact) 5.dp else 6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = color,
                fontSize = if (compact) 10.sp else 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** برچسب وضعیت (متصل/قطع/در حال اتصال) */
@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = V2.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = V2.TextMuted)
            }
        }
        trailing?.invoke()
    }
}

/** دکمه‌ی اصلی */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(V2.Mint, Color(0xFF37D6C0)))
                else Brush.horizontalGradient(listOf(V2.SurfaceHigh, V2.SurfaceHigh))
            )
            .clickable(enabled = enabled && !loading) { onClick() }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color(0xFF06201A),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color(0xFF06201A), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = if (enabled) Color(0xFF06201A) else V2.TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

/** دکمه‌ی فرعی */
@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(V2.Surface.copy(alpha = 0.8f))
            .border(1.dp, V2.Stroke, shape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = V2.TextSecondary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = V2.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** کارت خالی */
@Composable
fun EmptyState(title: String, description: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(V2.Mint.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = V2.Mint)
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = V2.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = V2.TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                PrimaryButton(text = actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** نوار پیشرفت اسکن هوشمند */
@Composable
fun ScanBanner(scan: ScanState) {
    AnimatedVisibility(visible = scan.running || (scan.finished && scan.message.isNotEmpty())) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scan.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = V2.Mint,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    if (scan.bestName.isNotEmpty()) V2.Mint else V2.Amber
                                )
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        scan.phase.ifBlank { "اسکن هوشمند" },
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (scan.total > 0) {
                        Text(
                            "${Format.number(scan.done)}/${Format.number(scan.total)}",
                            color = V2.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                if (scan.total > 0) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(V2.SurfaceHigh)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(scan.progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Brush.horizontalGradient(listOf(V2.Mint, V2.Sky)))
                        )
                    }
                }
                if (scan.message.isNotEmpty() && !scan.running) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        scan.message,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = V2.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** نوار پیشرفت همگام‌سازی خودکار اشتراک‌ها */
@Composable
fun SyncBanner(sync: SyncState) {
    AnimatedVisibility(visible = sync.running) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = V2.Sky,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sync.message.ifBlank { "همگام‌سازی منابع…" },
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (sync.total > 0) {
                        Text(
                            "${Format.number(sync.done)}/${Format.number(sync.total)}",
                            color = V2.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                if (sync.total > 0) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(V2.SurfaceHigh)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(sync.progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Brush.horizontalGradient(listOf(V2.Sky, V2.Mint)))
                        )
                    }
                }
            }
        }
    }
}

/** کارت خلاصه‌ی بررسی عمیق شبکه‌ی اپراتور */
@Composable
fun NetworkCard(network: NetworkAssessment, onRefresh: () -> Unit) {
    if (network.assessedAt == 0L) return
    GlassCard(Modifier.fillMaxWidth(), onClick = onRefresh) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                network.score >= 70 -> V2.Mint
                                network.score >= 45 -> V2.Amber
                                else -> V2.Red
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "شبکه: ${network.carrier.label}",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = V2.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${network.score}/۱۰۰",
                    color = V2.TextMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                network.message,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = V2.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TargetChip("تأخیر", network.latencyMs)
                if (network.jitterMs >= 0) TargetChip("لرزش", network.jitterMs)
                TargetChip("افت٪", if (network.lossPercent > 0) network.lossPercent else -1)
                if (network.dpiDetected) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(V2.Red.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("DPI", color = V2.Red, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** نوار اطلاع‌رسانی نسخه‌ی جدید */
@Composable
fun UpdateBanner(version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "نسخه‌ی ${version} منتشر شد",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = V2.Mint
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "برای دریافت آخرین نسخه ضربه بزنید",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = V2.TextMuted
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(V2.SurfaceHigh)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .clickable { onDismiss() }
            ) {
                Text("بعداً", color = V2.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

/** ردیف نشانگر پینگ گوگل/اینستاگرام/یوتیوب یک سرور */
@Composable
fun PingTargetsRow(google: Int, instagram: Int, youtube: Int) {
    if (google <= 0 && instagram <= 0 && youtube <= 0) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TargetChip("G", google)
        TargetChip("IG", instagram)
        TargetChip("YT", youtube)
    }
}

@Composable
private fun TargetChip(label: String, ping: Int) {
    val color = when {
        ping <= 0 -> V2.TextMuted.copy(alpha = 0.55f)
        ping < 300 -> V2.Mint
        ping < 700 -> V2.Amber
        else -> V2.Red
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$label ${if (ping > 0) Format.number(ping) else "—"}",
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** نشانگر نقطه‌ی چشمک‌زن (برای وضعیت در حال اتصال) */
@Composable
fun PulsingDot(color: Color, size: Int = 8) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ------------------------------------------------------------------ tabs

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("خانه", Icons.Filled.Home),
    SERVERS("سرورها", Icons.AutoMirrored.Filled.List),
    SUBS("اشتراک", Icons.Filled.Refresh),
    SETTINGS("تنظیمات", Icons.Filled.Settings)
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(shape)
            .background(Color(0xF20B131E))
            .border(1.dp, V2.Stroke.copy(alpha = 0.8f), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (active) V2.Mint.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) V2.Mint else V2.TextMuted,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    color = if (active) V2.Mint else V2.TextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
