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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.PerAppMode
import ir.v2max.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String)

@Composable
fun PerAppScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val selected = settings.perAppPackages

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName
            runCatching {
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != self }
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map {
                        AppEntry(
                            pkg = it.packageName,
                            label = runCatching { pm.getApplicationLabel(it).toString() }
                                .getOrDefault(it.packageName)
                        )
                    }
                    .sortedBy { it.label }
                    .toList()
            }.getOrDefault(emptyList())
        }
        apps = loaded
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, true) || it.pkg.contains(query, true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Text("→", color = V2.TextPrimary, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "پروکسی برنامه‌به‌برنامه",
                        style = MaterialTheme.typography.titleLarge,
                        color = V2.TextPrimary
                    )
                    Text(
                        "${Format.number(selected.size)} برنامه انتخاب شده",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("حالت", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChoice("همه", settings.perAppMode == PerAppMode.OFF, Modifier.weight(1f)) {
                            onChange { it.copy(perAppMode = PerAppMode.OFF) }
                        }
                        ModeChoice("فقط انتخاب‌شده‌ها", settings.perAppMode == PerAppMode.ONLY_SELECTED, Modifier.weight(1.4f)) {
                            onChange { it.copy(perAppMode = PerAppMode.ONLY_SELECTED) }
                        }
                        ModeChoice("به‌جز انتخاب‌شده‌ها", settings.perAppMode == PerAppMode.EXCEPT_SELECTED, Modifier.weight(1.4f)) {
                            onChange { it.copy(perAppMode = PerAppMode.EXCEPT_SELECTED) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (settings.perAppMode) {
                            PerAppMode.OFF -> "همه‌ی برنامه‌ها از تانل V2max عبور می‌کنند."
                            PerAppMode.ONLY_SELECTED -> "فقط برنامه‌های انتخاب‌شده از تانل عبور می‌کنند (بقیه با اینترنت معمولی)."
                            PerAppMode.EXCEPT_SELECTED -> "برنامه‌های انتخاب‌شده از تانل خارج می‌شوند (مثلاً بانک‌ها و اپ‌های ایرانی)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("جستجوی برنامه…", color = V2.TextMuted, fontSize = 12.5.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = V2.TextMuted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = null, tint = V2.TextMuted)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                shape = RoundedCornerShape(16.dp),
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

        if (loading) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = V2.Mint, strokeWidth = 2.dp)
                }
            }
        }

        items(filtered, key = { it.pkg }) { app ->
            val checked = app.pkg in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(V2.Surface.copy(alpha = 0.75f))
                    .clickable {
                        val newList = if (checked) selected - app.pkg else selected + app.pkg
                        onChange {
                            it.copy(
                                perAppPackages = newList,
                                perAppMode = if (it.perAppMode == PerAppMode.OFF) PerAppMode.EXCEPT_SELECTED else it.perAppMode
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(V2.Mint.copy(alpha = if (checked) 0.18f else 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        app.label.take(1),
                        color = V2.Mint,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        color = V2.TextPrimary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        app.pkg,
                        color = V2.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (checked) V2.Mint else V2.SurfaceHigh)
                        .border(1.dp, if (checked) V2.Mint else V2.Stroke, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (checked) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = V2.Background,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun ModeChoice(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) V2.Mint.copy(alpha = 0.16f) else V2.SurfaceHigh)
            .border(1.dp, if (active) V2.Mint.copy(alpha = 0.5f) else V2.Stroke, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) V2.Mint else V2.TextSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}
