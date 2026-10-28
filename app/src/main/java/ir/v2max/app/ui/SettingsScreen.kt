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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.RoutingMode
import ir.v2max.app.util.Format

@Composable
fun SettingsScreen(
    settings: AppSettings,
    coreVersion: String,
    appVersion: String,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearAll: () -> Unit,
    onOpenPerApp: () -> Unit = {},
    onSetFreeSources: (Boolean) -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {}
) {
    var showDnsDialog by remember { mutableStateOf(false) }
    var showMtuDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("تنظیمات", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
            Text(
                "تنظیمات را با دقت تغییر دهید؛ مقادیر پیش‌فرض برای بیشتر کاربران مناسب است",
                style = MaterialTheme.typography.bodySmall,
                color = V2.TextMuted
            )
        }

        item { GroupTitle("اتصال") }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "حالت مسیریابی",
                        style = MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (settings.routingMode == RoutingMode.SMART)
                            "سایت‌های ایرانی مستقیم باز می‌شوند و بقیه از تانل عبور می‌کنند (سریع‌تر و کم‌مصرف‌تر)."
                        else
                            "همه‌ی ترافیک از تانل عبور می‌کند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton(
                            text = "هوشمند",
                            active = settings.routingMode == RoutingMode.SMART,
                            modifier = Modifier.weight(1f)
                        ) { onChange { it.copy(routingMode = RoutingMode.SMART) } }
                        ModeButton(
                            text = "همه از تانل",
                            active = settings.routingMode == RoutingMode.GLOBAL,
                            modifier = Modifier.weight(1f)
                        ) { onChange { it.copy(routingMode = RoutingMode.GLOBAL) } }
                    }
                }
            }
        }

        item {
            SettingSwitch(
                title = "فرگمنت هوشمند (ضد فیلترینگ)",
                subtitle = "تکه‌تکه کردن بسته‌های TLS برای عبور از سیستم‌های DPI",
                checked = settings.fragment
            ) { value -> onChange { it.copy(fragment = value) } }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "شدت فرگمنت TLS",
                        style = MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "تعداد تکه‌ها و فاصله‌ی زمانی بین آن‌ها. اگر اتصال برقرار نشد یا کند بود، پروفایل دیگری را امتحان کنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ir.v2max.app.data.FragmentProfile.entries.forEach { profile ->
                            val active = settings.fragmentProfile == profile
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        1.dp,
                                        if (active) V2.Mint.copy(alpha = 0.6f) else V2.Stroke,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .background(if (active) V2.Mint.copy(alpha = 0.08f) else Color.Transparent)
                                    .clickable { onChange { it.copy(fragmentProfile = profile) } }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    profile.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (active) V2.Mint else V2.TextSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "تکه ${profile.maxSplit} · تأخیر ${profile.delay}",
                                    fontSize = 10.sp,
                                    color = V2.TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingSwitch(
                title = "پینگ چندمقصدی (گوگل، اینستاگرام، یوتیوب)",
                subtitle = "پیش از اتصال، سرور با هر سه مقصد سنجیده می‌شود و بهترین‌ها انتخاب می‌شوند",
                checked = settings.multiPing
            ) { value -> onChange { it.copy(multiPing = value) } }
        }

        item {
            SettingSwitch(
                title = "حذف سرورهای بدون پاسخ",
                subtitle = "سرورهایی که در تست‌ها به هیچ مقصدی وصل نمی‌شوند از فهرست پاک می‌شوند",
                checked = settings.dropDeadServers
            ) { value -> onChange { it.copy(dropDeadServers = value) } }
        }

        item {
            SettingSwitch(
                title = "به‌روزرسانی خودکار در شروع",
                subtitle = "در هر بار باز شدن برنامه، همه‌ی اشتراک‌ها تازه می‌شوند و بهترین سرور انتخاب می‌شود",
                checked = settings.autoRefreshOnOpen
            ) { value -> onChange { it.copy(autoRefreshOnOpen = value) } }
        }

        item {
            SettingSwitch(
                title = "منابع رایگان عمومی",
                subtitle = "افزودن خودکار چند مجموعه‌ی عمومی کانفیگ (بدون نیاز به اشتراک شخصی)",
                checked = settings.useFreeSources
            ) { value -> onSetFreeSources(value) }
        }

        item {
            SettingSwitch(
                title = "اتصال خودکار هنگام باز شدن برنامه",
                subtitle = "به محض ورود به V2max، اتصال برقرار می‌شود",
                checked = settings.autoConnectOnOpen
            ) { value -> onChange { it.copy(autoConnectOnOpen = value) } }
        }

        item {
            SettingSwitch(
                title = "اتصال خودکار پس از روشن شدن گوشی",
                subtitle = "نیازمند اجازه‌ی راه‌اندازی خودکار",
                checked = settings.autoConnectOnBoot
            ) { value -> onChange { it.copy(autoConnectOnBoot = value) } }
        }

        item {
            SettingSwitch(
                title = "سوییچ خودکار سرور",
                subtitle = "اگر سرور فعلی قطع شد، بلافاصله سرور سالمِ جایگزین انتخاب و متصل می‌شود",
                checked = settings.smartFailover
            ) { value -> onChange { it.copy(smartFailover = value) } }
        }

        item {
            SettingValue(
                title = "پروکسی برنامه‌به‌برنامه",
                subtitle = when (settings.perAppMode) {
                    ir.v2max.app.data.PerAppMode.OFF -> "همه‌ی برنامه‌ها از تانل عبور می‌کنند"
                    ir.v2max.app.data.PerAppMode.ONLY_SELECTED -> "فقط برنامه‌های انتخاب‌شده"
                    ir.v2max.app.data.PerAppMode.EXCEPT_SELECTED -> "به‌جز برنامه‌های انتخاب‌شده"
                },
                value = if (settings.perAppMode == ir.v2max.app.data.PerAppMode.OFF) "تنظیم"
                else "${Format.number(settings.perAppPackages.size)} برنامه"
            ) { onOpenPerApp() }
        }

        item { GroupTitle("شبکه") }

        item {
            SettingSwitch(
                title = "چندمسیره (Mux)",
                subtitle = "کاهش تأخیر در اتصال‌های همزمان؛ روی برخی سرورها اختلال ایجاد می‌کند",
                checked = settings.mux
            ) { value -> onChange { it.copy(mux = value) } }
        }

        item {
            SettingSwitch(
                title = "پشتیبانی IPv6",
                subtitle = "فقط اگر اپراتور و سرور شما IPv6 را پشتیبانی می‌کنند فعال کنید",
                checked = settings.ipv6
            ) { value -> onChange { it.copy(ipv6 = value) } }
        }

        item {
            SettingValue(
                title = "MTU",
                subtitle = "اندازه‌ی بسته‌های تانل (پیش‌فرض ${Format.number(1500)})",
                value = Format.number(settings.mtu)
            ) { showMtuDialog = true }
        }

        item {
            SettingValue(
                title = "تنظیمات DNS",
                subtitle = "DNS خارجی، داخلی و DNS دستگاه",
                value = "ویرایش"
            ) { showDnsDialog = true }
        }

        item { GroupTitle("نمایش") }

        item {
            SettingSwitch(
                title = "نمایش سرعت در نوتیفیکیشن",
                subtitle = "نمایش سرعت دانلود/آپلود و پینگ روی نوتیفیکیشن دائمی",
                checked = settings.showSpeedInNotification
            ) { value -> onChange { it.copy(showSpeedInNotification = value) } }
        }

        item { GroupTitle("پشتیبان و بازیابی") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    GhostButton(text = "پشتیبان‌گیری", modifier = Modifier.fillMaxWidth()) { onBackup() }
                }
                Box(Modifier.weight(1f)) {
                    GhostButton(text = "بازیابی", modifier = Modifier.fillMaxWidth()) { onRestore() }
                }
            }
        }

        item { GroupTitle("درباره") }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow("نسخه برنامه", appVersion)
                    InfoRow("هسته", coreVersion)
                    InfoRow("پروتکل‌ها", "VLESS · VMess · Trojan · SS · Hysteria2")
                    InfoRow("مجوز", "MIT / متن‌باز")
                }
            }
        }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, V2.Red.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .background(V2.Red.copy(alpha = 0.07f))
                    .clickable { showClearDialog = true }
                    .padding(16.dp)
            ) {
                Column {
                    Text("حذف همه‌ی داده‌ها", color = V2.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "همه‌ی اشتراک‌ها و سرورها پاک می‌شوند",
                        color = V2.TextMuted,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showMtuDialog) {
        var value by remember { mutableStateOf(settings.mtu.toString()) }
        AlertDialog(
            onDismissRequest = { showMtuDialog = false },
            containerColor = V2.SurfaceHigh,
            title = { Text("MTU", color = V2.TextPrimary) },
            text = {
                Column {
                    Text(
                        "مقدار پیشنهادی ۱۵۰۰ است. اگر اتصال ناپایدار بود، ۱۴۰۰ یا ۱۳۸۰ را امتحان کنید.",
                        color = V2.TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = Format.toEnglishDigits(it).filter { ch -> ch.isDigit() }.take(4) },
                        singleLine = true,
                        colors = dialogFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mtu = value.toIntOrNull()?.coerceIn(1280, 9000) ?: settings.mtu
                    onChange { it.copy(mtu = mtu) }
                    showMtuDialog = false
                }) { Text("ذخیره", color = V2.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showMtuDialog = false }) { Text("انصراف", color = V2.TextMuted) }
            }
        )
    }

    if (showDnsDialog) {
        var remote by remember { mutableStateOf(settings.remoteDns) }
        var domestic by remember { mutableStateOf(settings.domesticDns) }
        var vpn by remember { mutableStateOf(settings.vpnDns) }
        AlertDialog(
            onDismissRequest = { showDnsDialog = false },
            containerColor = V2.SurfaceHigh,
            title = { Text("تنظیمات DNS", color = V2.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = remote,
                        onValueChange = { remote = it },
                        label = { Text("DNS خارجی (DoH)", fontSize = 11.sp, color = V2.TextMuted) },
                        singleLine = true,
                        colors = dialogFieldColors()
                    )
                    OutlinedTextField(
                        value = domestic,
                        onValueChange = { domestic = it },
                        label = { Text("DNS داخلی (ایران)", fontSize = 11.sp, color = V2.TextMuted) },
                        singleLine = true,
                        colors = dialogFieldColors()
                    )
                    OutlinedTextField(
                        value = vpn,
                        onValueChange = { vpn = it },
                        label = { Text("DNS دستگاه", fontSize = 11.sp, color = V2.TextMuted) },
                        singleLine = true,
                        colors = dialogFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange {
                        it.copy(
                            remoteDns = remote.ifBlank { "https://1.1.1.1/dns-query" },
                            domesticDns = domestic.ifBlank { "178.22.122.100" },
                            vpnDns = vpn.ifBlank { "1.1.1.1" }
                        )
                    }
                    showDnsDialog = false
                }) { Text("ذخیره", color = V2.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showDnsDialog = false }) { Text("انصراف", color = V2.TextMuted) }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = V2.SurfaceHigh,
            title = { Text("حذف همه‌ی داده‌ها", color = V2.TextPrimary) },
            text = { Text("مطمئن هستید؟ این کار قابل بازگشت نیست.", color = V2.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearDialog = false
                }) { Text("حذف همه", color = V2.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("انصراف", color = V2.TextMuted) }
            }
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        color = V2.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = V2.Background,
                    checkedTrackColor = V2.Mint,
                    uncheckedThumbColor = V2.TextMuted,
                    uncheckedTrackColor = V2.SurfaceHigh,
                    uncheckedBorderColor = V2.Stroke
                )
            )
        }
    }
}

@Composable
private fun SettingValue(title: String, subtitle: String, value: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
            }
            Spacer(Modifier.width(10.dp))
            Text(value, color = V2.Mint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = V2.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = V2.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModeButton(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) V2.Mint.copy(alpha = 0.16f) else V2.SurfaceHigh)
            .border(
                1.dp,
                if (active) V2.Mint.copy(alpha = 0.5f) else V2.Stroke,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) V2.Mint else V2.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun dialogFieldColors(): androidx.compose.material3.TextFieldColors =
    OutlinedTextFieldDefaults.colors(
    focusedBorderColor = V2.Mint.copy(alpha = 0.6f),
    unfocusedBorderColor = V2.Stroke,
    focusedTextColor = V2.TextPrimary,
    unfocusedTextColor = V2.TextPrimary,
    focusedContainerColor = V2.Surface,
    unfocusedContainerColor = V2.Surface
)
