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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.data.ServerConfig

/**
 * ویرایشگر دستی کانفیگ + ساخت نسخه‌ی کپی.
 * برای سرورهایی که نیاز به اصلاح دستی پارامترها دارند (SNI، path، flow و…).
 */
@Composable
fun ConfigEditorScreen(
    server: ServerConfig,
    dataEpoch: Long,
    onBack: () -> Unit,
    onSave: (ServerConfig, String, Long) -> Unit,
    onClone: (ServerConfig, String, Long) -> Unit
) {
    // Keep the opening snapshot even if a subscription refresh recomposes this screen.
    val original = remember(server.id) { server.copy() }
    val openedEpoch = remember(server.id) { dataEpoch }
    var name by remember(server.id) { mutableStateOf(original.name) }
    var address by remember(server.id) { mutableStateOf(original.address) }
    var port by remember(server.id) { mutableStateOf(original.port.toString()) }
    var uuid by remember(server.id) { mutableStateOf(original.uuid) }
    var password by remember(server.id) { mutableStateOf(original.password) }
    var method by remember(server.id) { mutableStateOf(original.method) }
    var flow by remember(server.id) { mutableStateOf(original.flow) }
    var network by remember(server.id) { mutableStateOf(original.network) }
    var security by remember(server.id) { mutableStateOf(original.security) }
    var sni by remember(server.id) { mutableStateOf(original.sni) }
    var host by remember(server.id) { mutableStateOf(original.host) }
    var path by remember(server.id) { mutableStateOf(original.path) }
    var serviceName by remember(server.id) { mutableStateOf(original.serviceName) }
    var publicKey by remember(server.id) { mutableStateOf(original.publicKey) }
    var shortId by remember(server.id) { mutableStateOf(original.shortId) }
    var fingerprint by remember(server.id) { mutableStateOf(original.fingerprint) }
    var alpn by remember(server.id) { mutableStateOf(original.alpn) }
    var validationError by remember(server.id) { mutableStateOf("") }

    fun collect(): ServerConfig? {
        val result = EditorValidation.validate(address, port, network, security, original.proto)
        if (result is EditorValidation.Result.Invalid) {
            validationError = result.message
            return null
        }
        val fields = (result as EditorValidation.Result.Valid).fields
        validationError = ""
        return original.copy(
            name = name.trim().ifBlank { original.name },
            address = address.trim(),
            port = fields.port,
            uuid = uuid.trim(),
            password = password.trim(),
            method = method.trim(),
            flow = flow.trim(),
            network = fields.network,
            security = fields.security,
            sni = sni.trim(),
            host = host.trim(),
            path = path.trim(),
            serviceName = serviceName.trim(),
            publicKey = publicKey.trim(),
            shortId = shortId.trim(),
            fingerprint = fingerprint.trim(),
            alpn = alpn.trim()
        )
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
                    Text("ویرایش کانفیگ", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
                    Text(original.proto.name, style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditField("نام", name) { name = it }
                    EditField("آدرس (IP یا دامنه)", address) { address = it }
                    EditField("پورت", port, KeyboardType.Number) { port = it }
                    when (original.proto) {
                        ir.v2max.app.data.Proto.SS -> {
                            EditField("روش رمزنگاری", method) { method = it }
                            EditField("رمز", password) { password = it }
                        }
                        ir.v2max.app.data.Proto.TROJAN -> {
                            EditField("رمز", password) { password = it }
                            EditField("Flow (اختیاری)", flow) { flow = it }
                        }
                        ir.v2max.app.data.Proto.HYSTERIA2 ->
                            EditField("رمز یا کلید احراز هویت", password) { password = it }
                        ir.v2max.app.data.Proto.SOCKS, ir.v2max.app.data.Proto.HTTP -> {
                            EditField("نام کاربری (اختیاری)", uuid) { uuid = it }
                            EditField("رمز (اختیاری)", password) { password = it }
                        }
                        ir.v2max.app.data.Proto.VLESS -> {
                            EditField("UUID / شناسه", uuid) { uuid = it }
                            EditField("Encryption (پیش‌فرض none)", method) { method = it }
                            EditField("Flow (اختیاری)", flow) { flow = it }
                        }
                        ir.v2max.app.data.Proto.VMESS -> {
                            EditField("UUID / شناسه", uuid) { uuid = it }
                            EditField("روش رمزنگاری (پیش‌فرض auto)", method) { method = it }
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("انتقال", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    if (original.proto == ir.v2max.app.data.Proto.HYSTERIA2) {
                        Text("انتقال Hysteria2 به‌صورت خودکار تنظیم می‌شود", style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                    } else {
                        EditField("شبکه (tcp/ws/grpc/xhttp/kcp)", network) { network = it }
                        EditField("Host", host) { host = it }
                        EditField("Path", path) { path = it }
                        EditField("Service Name (gRPC)", serviceName) { serviceName = it }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("امنیت", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    if (original.proto == ir.v2max.app.data.Proto.HYSTERIA2) {
                        Text("Hysteria2 از TLS استفاده می‌کند", style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                    } else {
                        EditField("نوع (tls/reality/none)", security) { security = it }
                    }
                    EditField("SNI", sni) { sni = it }
                    EditField("ALPN", alpn) { alpn = it }
                    EditField("Fingerprint", fingerprint) { fingerprint = it }
                    if (original.proto != ir.v2max.app.data.Proto.HYSTERIA2) {
                        EditField("Public Key (Reality)", publicKey) { publicKey = it }
                        EditField("Short ID (Reality)", shortId) { shortId = it }
                    }
                }
            }
        }

        item {
            if (validationError.isNotBlank()) {
                Text(validationError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(text = "ذخیره", modifier = Modifier.weight(1f)) {
                    collect()?.let { onSave(it, original.fingerprint(), openedEpoch) }
                }
                GhostButton(text = "ساخت کپی", modifier = Modifier.weight(1f)) {
                    collect()?.let {
                        onClone(it.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            name = (name.trim().ifBlank { original.name }) + " (کپی)"
                        ), original.fingerprint(), openedEpoch)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 11.sp, color = V2.TextMuted) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = V2.TextPrimary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = V2.Mint,
            unfocusedBorderColor = V2.Stroke,
            cursorColor = V2.Mint
        )
    )
}
