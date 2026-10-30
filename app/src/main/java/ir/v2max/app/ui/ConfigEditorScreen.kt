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
    onBack: () -> Unit,
    onSave: (ServerConfig) -> Unit,
    onClone: (ServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var address by remember { mutableStateOf(server.address) }
    var port by remember { mutableStateOf(server.port.toString()) }
    var uuid by remember { mutableStateOf(server.uuid) }
    var password by remember { mutableStateOf(server.password) }
    var method by remember { mutableStateOf(server.method) }
    var flow by remember { mutableStateOf(server.flow) }
    var network by remember { mutableStateOf(server.network) }
    var security by remember { mutableStateOf(server.security) }
    var sni by remember { mutableStateOf(server.sni) }
    var host by remember { mutableStateOf(server.host) }
    var path by remember { mutableStateOf(server.path) }
    var serviceName by remember { mutableStateOf(server.serviceName) }
    var publicKey by remember { mutableStateOf(server.publicKey) }
    var shortId by remember { mutableStateOf(server.shortId) }
    var fingerprint by remember { mutableStateOf(server.fingerprint) }
    var alpn by remember { mutableStateOf(server.alpn) }

    fun collect(): ServerConfig = server.copy(
        name = name.trim().ifBlank { server.name },
        address = address.trim(),
        port = port.toIntOrNull()?.coerceIn(1, 65535) ?: server.port,
        uuid = uuid.trim(),
        password = password.trim(),
        method = method.trim(),
        flow = flow.trim(),
        network = network.trim().ifBlank { "tcp" },
        security = security.trim(),
        sni = sni.trim(),
        host = host.trim(),
        path = path.trim(),
        serviceName = serviceName.trim(),
        publicKey = publicKey.trim(),
        shortId = shortId.trim(),
        fingerprint = fingerprint.trim(),
        alpn = alpn.trim()
    )

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
                    Text(server.proto.name, style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditField("نام", name) { name = it }
                    EditField("آدرس (IP یا دامنه)", address) { address = it }
                    EditField("پورت", port, KeyboardType.Number) { port = it }
                    if (server.proto == ir.v2max.app.data.Proto.SS) {
                        EditField("روش رمزنگاری", method) { method = it }
                        EditField("رمز", password) { password = it }
                    } else {
                        EditField("UUID / شناسه", uuid) { uuid = it }
                        EditField("Flow (اختیاری)", flow) { flow = it }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("انتقال", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    EditField("شبکه (tcp/ws/grpc/http/h2/quic)", network) { network = it }
                    EditField("Host", host) { host = it }
                    EditField("Path", path) { path = it }
                    EditField("Service Name (gRPC)", serviceName) { serviceName = it }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("امنیت", style = MaterialTheme.typography.titleSmall, color = V2.TextPrimary)
                    EditField("نوع (tls/reality/none)", security) { security = it }
                    EditField("SNI", sni) { sni = it }
                    EditField("ALPN", alpn) { alpn = it }
                    EditField("Fingerprint", fingerprint) { fingerprint = it }
                    EditField("Public Key (Reality)", publicKey) { publicKey = it }
                    EditField("Short ID (Reality)", shortId) { shortId = it }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(text = "ذخیره", modifier = Modifier.weight(1f)) {
                    onSave(collect())
                }
                GhostButton(text = "ساخت کپی", modifier = Modifier.weight(1f)) {
                    onClone(collect().copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = (name.trim().ifBlank { server.name }) + " (کپی)",
                        tcpPing = -1,
                        realPing = -1,
                        googlePing = -1,
                        instagramPing = -1,
                        youtubePing = -1,
                        speedKbps = 0,
                        successCount = 0,
                        failCount = 0,
                        testedAt = 0L,
                        addedAt = System.currentTimeMillis()
                    ))
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
