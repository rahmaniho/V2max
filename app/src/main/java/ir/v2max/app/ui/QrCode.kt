package ir.v2max.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** کد QR یک متن (لینک کانفیگ یا اشتراک) را به بیت‌مپ تبدیل می‌کند */
fun qrBitmap(content: String, size: Int = 720): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    bitmap
}.getOrNull()

/** دیالوگ نمایش QR با امکان بستن */
@Composable
fun QrDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val bitmap = remember(content) { qrBitmap(content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = V2.SurfaceHigh,
        title = { Text(title, color = V2.TextPrimary) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bitmap != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(10.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR",
                            modifier = Modifier.size(230.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        "ساخت کد QR ناموفق بود",
                        color = V2.Red,
                        fontSize = 12.sp
                    )
                }
                Text(
                    content.take(120) + if (content.length > 120) "…" else "",
                    color = V2.TextMuted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text("کپی لینک", color = V2.Mint) }
                TextButton(onClick = onDismiss) { Text("بستن", color = V2.TextMuted) }
            }
        }
    )
}

/** نسخه‌ی ساده‌شده برای فهرست سرورها */
@Composable
fun QrIconHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = V2.TextMuted)
}
