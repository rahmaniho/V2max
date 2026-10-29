package ir.v2max.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * اسکنر QR برای افزودن مخزن/لینک اشتراک.
 * از CameraX برای پیش‌نمایش و از ZXing برای رمزگشایی استفاده می‌کند.
 */
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var handled by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(hasPermission) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!hasPermission) return@addListener
            runCatching {
                provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy ->
                    if (handled) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    try {
                        decodeQr(proxy)?.let { text ->
                            handled = true
                            previewView.post { onScanned(text) }
                        }
                    } catch (t: Throwable) {
                        error = "خطا در خواندن تصویر"
                    } finally {
                        proxy.close()
                    }
                }

                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure { error = "دوربین در دسترس نیست" }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            runCatching { executor.shutdown() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("اسکن QR اشتراک", style = MaterialTheme.typography.headlineMedium, color = V2.TextPrimary)
        Text(
            "کد QR مخزن یا لینک اشتراک را داخل کادر قرار دهید",
            style = MaterialTheme.typography.bodySmall,
            color = V2.TextMuted
        )
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(V2.Surface)
                .border(1.dp, V2.Stroke, RoundedCornerShape(20.dp))
        ) {
            if (hasPermission) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
                // قاب راهنما
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.66f)
                        .height(220.dp)
                        .border(2.dp, V2.Mint.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "برای اسکن QR به دوربین نیاز است",
                        color = V2.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    GhostButton(text = "اجازه‌ی دوربین", modifier = Modifier.fillMaxWidth()) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = V2.Red, fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(V2.Surface.copy(alpha = 0.8f))
                .border(1.dp, V2.Stroke, RoundedCornerShape(18.dp))
                .clickable { onBack() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "بازگشت",
                color = V2.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** رمزگشایی یک فریم دوربین */
private fun decodeQr(proxy: ImageProxy): String? {
    val image = proxy.image ?: return null
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    val width = image.width
    val height = image.height
    // برای تصاویر YUV، کانال Y (روشنایی) به‌تنهایی برای QR کافی است
    var source = PlanarYUVLuminanceSource(data, plane.rowStride, height, 0, 0, width, height, false)
    if (source.width <= 0 || source.height <= 0) {
        source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    }

    return runCatching {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(
                        com.google.zxing.BarcodeFormat.QR_CODE
                    ),
                    com.google.zxing.DecodeHintType.TRY_HARDER to true
                )
            )
        }.decode(bitmap).text
    }.getOrNull()
}
