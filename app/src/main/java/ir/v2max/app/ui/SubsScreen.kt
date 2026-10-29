package ir.v2max.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.SyncState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.Subscription
import ir.v2max.app.util.Format

@Composable
fun SubsScreen(
    servers: List<ServerConfig>,
    subs: List<Subscription>,
    scan: ScanState,
    sync: SyncState,
    busy: Boolean,
    onAdd: (String, String) -> Unit,
    onRefresh: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onDelete: (String) -> Unit,
    onSmartScan: (String) -> Unit,
    onImportText: (String) -> Unit,
    onAddFreeSources: () -> Unit = {},
    onScanQr: () -> Unit = {}
) {
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Subscription?>(null) }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("اشتراک‌ها", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
                    Text(
                        "منبع کانفیگ‌های شما",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                }
                GhostButton(
                    text = "QR",
                    modifier = Modifier.width(72.dp),
                    onClick = onScanQr
                )
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    text = "افزودن",
                    modifier = Modifier.width(104.dp),
                    icon = Icons.Filled.Add,
                    onClick = {
                        showAdd = true
                        url = clipboard.getText()?.text?.trim().orEmpty()
                    }
                )
            }
        }

        item { SyncBanner(sync) }

        item { ScanBanner(scan) }

        item {
            AnimatedVisibility(visible = showAdd) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "افزودن اشتراک جدید",
                            style = MaterialTheme.typography.titleSmall,
                            color = V2.TextPrimary
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("نام دلخواه (اختیاری)", color = V2.TextMuted, fontSize = 12.5.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://… لینک اشتراک", color = V2.TextMuted, fontSize = 12.5.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                text = "دریافت و اتصال هوشمند",
                                modifier = Modifier.weight(1f),
                                loading = busy,
                                onClick = {
                                    onAdd(name, url)
                                    showAdd = false
                                    name = ""
                                    url = ""
                                }
                            )
                            GhostButton(
                                text = "بستن",
                                modifier = Modifier.width(84.dp),
                                onClick = { showAdd = false }
                            )
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(V2.Sky.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔗", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "افزودن سریع لینک کانفیگ",
                            style = MaterialTheme.typography.titleSmall,
                            color = V2.TextPrimary
                        )
                        Text(
                            "لینک vmess / vless / trojan را از کلیپ‌بورد اضافه کنید",
                            style = MaterialTheme.typography.bodySmall,
                            color = V2.TextMuted
                        )
                    }
                    TextButton(onClick = {
                        val text = clipboard.getText()?.text
                        if (!text.isNullOrBlank()) onImportText(text)
                    }) {
                        Text("چسباندن", color = V2.Mint, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "منابع رایگان عمومی",
                            style = MaterialTheme.typography.titleSmall,
                            color = V2.TextPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "چند مجموعه‌ی عمومی کانفیگ که به‌طور خودکار به‌روزرسانی می‌شوند",
                            style = MaterialTheme.typography.bodySmall,
                            color = V2.TextMuted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    GhostButton(
                        text = "افزودن",
                        modifier = Modifier.width(92.dp),
                        onClick = onAddFreeSources
                    )
                }
            }
        }

        if (subs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "اشتراک‌های من",
                        style = MaterialTheme.typography.titleMedium,
                        color = V2.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefreshAll) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = V2.Mint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("به‌روزرسانی همه", color = V2.Mint, fontSize = 12.sp)
                    }
                }
            }
        }

        items(subs, key = { it.id }) { sub ->
            SubscriptionCard(
                sub = sub,
                serverCount = servers.count { it.subId == sub.id },
                onRefresh = { onRefresh(sub.id) },
                onSmartScan = { onSmartScan(sub.id) },
                onDelete = { pendingDelete = sub }
            )
        }

        if (subs.isEmpty()) {
            item {
                EmptyState(
                    title = "اشتراکی ثبت نشده",
                    description = "لینک اشتراک (Subscription) خود را وارد کنید تا V2max کانفیگ‌ها را دریافت، تست و بهترین را انتخاب کند."
                )
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
    }

    pendingDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = V2.SurfaceHigh,
            title = { Text("حذف اشتراک", color = V2.TextPrimary) },
            text = {
                Text(
                    "اشتراک «${sub.name}» و همه‌ی کانفیگ‌های آن حذف شود؟",
                    color = V2.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(sub.id)
                    pendingDelete = null
                }) { Text("حذف", color = V2.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("انصراف", color = V2.TextMuted) }
            }
        )
    }
}

@Composable
private fun SubscriptionCard(
    sub: Subscription,
    serverCount: Int,
    onRefresh: () -> Unit,
    onSmartScan: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sub.name.ifBlank { "اشتراک" },
                            style = MaterialTheme.typography.titleSmall,
                            color = V2.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (sub.builtIn) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(V2.Sky.copy(alpha = 0.14f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("رایگان", color = V2.Sky, fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(V2.Mint.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "${Format.number(serverCount)} سرور",
                        color = V2.Mint,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (sub.total > 0L) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(V2.SurfaceHigh)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(sub.usedPercent())
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    if (sub.usedPercent() > 0.85f) listOf(V2.Red, V2.Amber)
                                    else listOf(V2.Mint, V2.Sky)
                                )
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        "${Format.bytes(sub.usedBytes)} از ${Format.bytes(sub.total)}",
                        fontSize = 10.5.sp,
                        color = V2.TextSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (sub.expireAt > 0) "انقضا: ${Format.dateOnly(sub.expireAt * 1000)}" else "آخرین به‌روزرسانی: ${Format.dateOnly(sub.updatedAt)}",
                        fontSize = 10.5.sp,
                        color = V2.TextMuted
                    )
                }
            } else {
                Text(
                    "آخرین به‌روزرسانی: ${Format.dateOnly(sub.updatedAt)}",
                    fontSize = 10.5.sp,
                    color = V2.TextMuted
                )
            }

            if (sub.lastError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(sub.lastError, fontSize = 10.5.sp, color = V2.Red, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(text = "اسکن هوشمند", color = V2.Mint, modifier = Modifier.weight(1f), onClick = onSmartScan)
                SmallAction(text = "به‌روزرسانی", color = V2.Sky, modifier = Modifier.weight(1f), onClick = onRefresh)
                SmallAction(text = "حذف", color = V2.Red, modifier = Modifier.width(72.dp), onClick = onDelete)
            }
        }
    }
}

@Composable
private fun SmallAction(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun fieldColors(): androidx.compose.material3.TextFieldColors =
    OutlinedTextFieldDefaults.colors(
    focusedBorderColor = V2.Mint.copy(alpha = 0.6f),
    unfocusedBorderColor = V2.Stroke,
    focusedContainerColor = V2.Surface.copy(alpha = 0.6f),
    unfocusedContainerColor = V2.Surface.copy(alpha = 0.6f),
    focusedTextColor = V2.TextPrimary,
    unfocusedTextColor = V2.TextPrimary
)
