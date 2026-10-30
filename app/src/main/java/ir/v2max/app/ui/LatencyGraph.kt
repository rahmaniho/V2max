package ir.v2max.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.v2max.app.util.Format

/**
 * کارت کیفیت اتصال: نمودار زنده‌ی تأخیر (۶۰ نمونه‌ی آخر) + میانگین و لرزش (jitter).
 * هدف: کاربر با یک نگاه بفهمد مسیر سالم است یا نه؛ بدون خواندن عدد و اصطلاح فنی.
 */
@Composable
fun LatencyCard(
    samples: List<Int>,
    poolSize: Int,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val avg = if (samples.isEmpty()) 0 else samples.average().toInt()
    val last = samples.lastOrNull() ?: 0
    val jitter = if (samples.size < 3) 0 else {
        samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toInt()
    }
    val quality = when {
        samples.isEmpty() -> "بدون داده"
        avg < 120 && jitter < 40 -> "عالی"
        avg < 250 && jitter < 90 -> "خوب"
        avg < 450 -> "متوسط"
        else -> "ضعیف"
    }
    val accent = when (quality) {
        "عالی" -> V2.Mint
        "خوب" -> V2.Sky
        "متوسط" -> V2.Amber
        else -> V2.Red
    }

    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "کیفیت مسیر",
                        style = MaterialTheme.typography.titleSmall,
                        color = V2.TextPrimary
                    )
                    Text(
                        if (!connected) "برای مشاهده‌ی نمودار متصل شوید"
                        else "میانگین ${Format.number(avg)}ms • لرزش ${Format.number(jitter)}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = V2.TextMuted
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(quality, color = accent, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(V2.Background.copy(alpha = 0.45f))
            ) {
                if (samples.size >= 2) {
                    val min = samples.min().coerceAtLeast(1)
                    val max = samples.max().coerceAtLeast(min + 1)
                    Canvas(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
                        val w = size.width
                        val h = size.height
                        val stepX = w / (samples.size - 1).coerceAtLeast(1)
                        val path = Path()
                        val fill = Path()
                        samples.forEachIndexed { index, value ->
                            val norm = ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
                            val x = index * stepX
                            val y = h - norm * h
                            if (index == 0) {
                                path.moveTo(x, y)
                                fill.moveTo(x, h)
                                fill.lineTo(x, y)
                            } else {
                                path.lineTo(x, y)
                                fill.lineTo(x, y)
                            }
                        }
                        fill.lineTo(w, h)
                        fill.close()

                        drawPath(
                            path = fill,
                            brush = Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))
                            )
                        )
                        drawPath(
                            path = path,
                            color = accent,
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                        )
                        drawCircle(
                            color = accent,
                            radius = 3.5f,
                            center = Offset(
                                (samples.size - 1) * stepX,
                                h - ((samples.last() - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f) * h
                            )
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                        Text("در انتظار نمونه‌های تأخیر…", fontSize = 10.sp, color = V2.TextMuted)
                    }
                }
            }

            if (poolSize > 1) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "استخر فعال: ${Format.number(poolSize)} سرور",
                        fontSize = 10.sp,
                        color = V2.Mint
                    )
                    Text(
                        "— هسته سریع‌ترین را انتخاب می‌کند",
                        fontSize = 10.sp,
                        color = V2.TextMuted
                    )
                }
            }
            if (last > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "آخرین نمونه: ${Format.number(last)}ms",
                    fontSize = 10.sp,
                    color = V2.TextMuted
                )
            }
        }
    }
}
