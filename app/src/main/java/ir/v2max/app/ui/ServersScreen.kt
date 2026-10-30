package ir.v2max.app.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.ScanState
import ir.v2max.app.data.ServerConfig
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.data.TunnelUiState
import ir.v2max.app.util.Format

private enum class SortMode(val label: String) {
    FASTEST("بهترین امتیاز"),
    SPEED("سرعت"),
    STABLE("پایداری"),
    NAME("نام"),
    NEWEST("جدیدترین")
}

@Composable
fun ServersScreen(
    servers: List<ServerConfig>,
    selectedId: String,
    tunnel: TunnelUiState,
    scan: ScanState,
    onSelect: (String) -> Unit,
    onConnect: (String) -> Unit,
    onTest: (String) -> Unit,
    onTestAll: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDelete: (String) -> Unit,
    showFlags: Boolean = true,
    onCopyLink: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.FASTEST) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    val filtered = remember(servers, query, sort, onlyFavorites) {
        servers
            .filter { cfg ->
                (!onlyFavorites || cfg.favorite) &&
                    (query.isBlank() ||
                        cfg.displayName().contains(query, true) ||
                        cfg.address.contains(query, true) ||
                        cfg.proto.label.contains(query, true))
            }
            .sortedWith(
                when (sort) {
                    FASTEST -> compareBy { it.score() }
                    SortMode.NAME -> compareBy { it.displayName() }
                    SortMode.NEWEST -> compareByDescending { it.addedAt }
                }
            )
    }

    val alive = servers.count { it.tcpPing > 0 || it.realPing > 0 }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("سرورها", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
                    Text(
                        "${Format.number(servers.size)} کانفیگ • ${Format.number(alive)} سالم",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                }
                GhostButton(
                    text = if (scan.running) "در حال تست" else "تست همه",
                    modifier = Modifier.width(112.dp),
                    icon = Icons.Filled.Refresh,
                    onClick = onTestAll
                )
            }
        }

        item { ScanBanner(scan) }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("جستجوی سرور…", color = V2.TextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = V2.TextMuted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = null, tint = V2.TextMuted)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = V2.Mint.copy(alpha = 0.6f),
                    unfocusedBorderColor = V2.Stroke,
                    focusedContainerColor = V2.Surface.copy(alpha = 0.6f),
                    unfocusedContainerColor = V2.Surface.copy(alpha = 0.6f),
                    focusedTextColor = V2.TextPrimary,
                    unfocusedTextColor = V2.TextPrimary
                )
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortMode.entries.forEach { mode ->
                    Chip(
                        text = mode.label,
                        active = sort == mode,
                        onClick = { sort = mode }
                    )
                }
                Chip(
                    text = "★ موردعلاقه",
                    active = onlyFavorites,
                    onClick = { onlyFavorites = !onlyFavorites }
                )
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    title = if (servers.isEmpty()) "لیست سرورها خالی است" else "نتیجه‌ای یافت نشد",
                    description = if (servers.isEmpty())
                        "از بخش «اشتراک» لینک اشتراک خود را اضافه کنید یا یک لینک کانفیگ را از کلیپ‌بورد وارد کنید."
                    else "فیلتر یا عبارت جستجو را تغییر دهید."
                )
            }
        }

        items(filtered, key = { it.id }) { server ->
            ServerRow(
                server = server,
                showFlags = showFlags,
                selected = server.id == selectedId,
                connected = tunnel.status == TunnelStatus.CONNECTED && tunnel.serverId == server.id,
                onClick = { onSelect(server.id) },
                onConnect = { onConnect(server.id) },
                onTest = { onTest(server.id) },
                onToggleFavorite = { onToggleFavorite(server.id) },
                onCopyLink = { onCopyLink(server.id) },
                onDelete = { pendingDelete = server }
            )
        }

        item { Spacer(Modifier.height(10.dp)) }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = V2.SurfaceHigh,
            title = { Text("حذف سرور", color = V2.TextPrimary) },
            text = {
                Text(
                    "«${server.displayName()}» حذف شود؟",
                    color = V2.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(server.id)
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
private fun ServerRow(
    server: ServerConfig,
    showFlags: Boolean,
    selected: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    val borderColor = when {
        connected -> V2.Mint.copy(alpha = 0.7f)
        selected -> V2.Mint.copy(alpha = 0.35f)
        else -> V2.Stroke.copy(alpha = 0.8f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (selected) V2.Mint.copy(alpha = 0.07f) else V2.Surface.copy(alpha = 0.9f),
                        Color(0xFF0A121D)
                    )
                )
            )
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) V2.Mint.copy(alpha = 0.18f) else V2.SurfaceHigh
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (connected) {
                    PulsingDot(V2.Mint, 10)
                } else {
                    Text(
                        server.proto.label.take(3),
                        color = if (selected) V2.Mint else V2.TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (server.favorite) {
                        Text("★", color = V2.Amber, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (showFlags && server.hasFlag()) {
                        Text(
                            ir.v2max.app.data.CountryFlags.emojiFor(server.countryCode),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        server.displayName(),
                        style = MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${server.address}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = V2.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (server.reachCount() > 0) {
                        PingTargetsRow(server.googlePing, server.instagramPing, server.youtubePing)
                    }
                    if (server.speedKbps > 0) {
                        Spacer(Modifier.height(4.dp))
                        StatusTag(Format.kbps(server.speedKbps), V2.Mint)
                    }
                    if (server.successCount + server.failCount >= 3) {
                        StatusTag("پایداری ${server.stability()}٪",
                            if (server.stability() >= 80) V2.Mint else if (server.stability() >= 50) V2.Amber else V2.Red)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PingBadge(server.bestPing(), compact = true)
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (server.favorite) V2.Amber else V2.TextMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = V2.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = V2.SurfaceHigh
                ) {
                    DropdownMenuItem(
                        text = { Text("اتصال به این سرور", color = V2.TextPrimary, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onConnect() }
                    )
                    DropdownMenuItem(
                        text = { Text("تست سرعت پاسخ", color = V2.TextPrimary, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onTest() }
                    )
                    DropdownMenuItem(
                        text = { Text("کپی لینک", color = V2.TextPrimary, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onCopyLink() }
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = V2.Red, modifier = Modifier.size(16.dp))
                        },
                        text = { Text("حذف", color = V2.Red, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) V2.Mint.copy(alpha = 0.16f) else V2.Surface.copy(alpha = 0.7f))
            .border(
                1.dp,
                if (active) V2.Mint.copy(alpha = 0.5f) else V2.Stroke,
                RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (active) V2.Mint else V2.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
