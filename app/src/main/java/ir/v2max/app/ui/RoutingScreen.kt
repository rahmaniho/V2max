package ir.v2max.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.RoutingMode

/**
 * ویرایشگر قواعد مسیریابی سفارشی.
 * هر قاعده: action|type|value — مثلاً «direct|domain|digikala.com»
 */
@Composable
fun RoutingScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit
) {
    var newRule by remember { mutableStateOf("") }
    val rules = settings.customRoutingRules

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
                    Text("مسیریابی", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
                    Text("قواعد دلخواه برای دامنه‌ها، IPها و پورت‌ها", style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("حالت کلی", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(
                            text = "هوشمند (ایران مستقیم)",
                            modifier = Modifier.weight(1f)
                        ) { onChange { it.copy(routingMode = RoutingMode.SMART) } }
                        GhostButton(
                            text = "همه از تانل",
                            modifier = Modifier.weight(1f)
                        ) { onChange { it.copy(routingMode = RoutingMode.GLOBAL) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (settings.routingMode == RoutingMode.SMART) "حالت فعال: هوشمند" else "حالت فعال: همه از تانل",
                        fontSize = 10.sp,
                        color = V2.Mint
                    )
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("افزودن قاعده", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "قالب: عمل|نوع|مقدار — مثال: direct|domain|digikala.com یا proxy|ip|8.8.8.8/32 یا block|domain|ads.example.org",
                        fontSize = 10.sp,
                        color = V2.TextMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newRule,
                        onValueChange = { newRule = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("direct|domain|example.com", fontSize = 12.sp, color = V2.TextMuted) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = V2.TextPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = V2.Mint,
                            unfocusedBorderColor = V2.Stroke,
                            cursorColor = V2.Mint
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(text = "افزودن", modifier = Modifier.weight(1f)) {
                            val rule = newRule.trim()
                            if (isValidRule(rule)) {
                                onChange { it.copy(customRoutingRules = it.customRoutingRules + rule) }
                                newRule = ""
                            }
                        }
                        GhostButton(text = "چسباندن از کلیپ‌بورد", modifier = Modifier.weight(1f)) {
                            val text = clipboardText()
                            if (text.isNotBlank()) newRule = text.trim().take(300)
                        }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                EmptyState(
                    title = "قاعده‌ای تعریف نشده",
                    description = "بدون قاعده‌ی سفارشی، ترافیک بر اساس حالت کلی مسیریابی می‌شود."
                )
            }
        } else {
            item {
                Text(
                    "${rules.size} قاعده",
                    style = MaterialTheme.typography.titleSmall,
                    color = V2.TextPrimary
                )
            }
            itemsIndexed(rules) { index, rule ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.split("|").firstOrNull().orEmpty(),
                                fontSize = 11.sp,
                                color = when (rule.substringBefore("|")) {
                                    "direct" -> V2.Mint
                                    "proxy" -> V2.Sky
                                    else -> V2.Red
                                }
                            )
                            Text(rule, fontSize = 12.sp, color = V2.TextPrimary)
                        }
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = V2.TextMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    onChange {
                                        it.copy(customRoutingRules = it.customRoutingRules.filterIndexed { i, _ -> i != index })
                                    }
                                }
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun isValidRule(rule: String): Boolean {
    val parts = rule.split("|")
    if (parts.size < 3) return false
    if (parts[0].lowercase() !in listOf("direct", "proxy", "block", "reject")) return false
    if (parts[1].lowercase() !in listOf("domain", "ip", "port")) return false
    return parts.drop(2).any { it.isNotBlank() }
}

@Composable
private fun clipboardText(): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val manager = ctx.getSystemService(android.content.ClipboardManager::class.java)
    val clip = manager?.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx).toString()
}
