package ir.v2max.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import ir.v2max.app.R

/** پالت رنگی V2max */
object V2 {
    val Background = Color(0xFF060A13)
    val BackgroundDeep = Color(0xFF03060C)
    val Surface = Color(0xFF0E1622)
    val SurfaceHigh = Color(0xFF16202F)
    val Stroke = Color(0xFF223047)

    val Mint = Color(0xFF26E0A6)
    val MintDark = Color(0xFF12A87B)
    val Sky = Color(0xFF38BDF8)
    val Amber = Color(0xFFF5B942)
    val Red = Color(0xFFF87171)

    val TextPrimary = Color(0xFFEAF2FF)
    val TextSecondary = Color(0xFF93A6C0)
    val TextMuted = Color(0xFF5F7189)
}

val VazirFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

private val V2Typography = Typography(
    displayLarge = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp),
    headlineLarge = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineMedium = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = VazirFamily, fontWeight = FontWeight.Medium, fontSize = 10.5.sp)
)

private val V2Colors = darkColorScheme(
    primary = V2.Mint,
    onPrimary = Color(0xFF00251A),
    primaryContainer = V2.MintDark,
    onPrimaryContainer = Color(0xFFE6FFF6),
    secondary = V2.Sky,
    onSecondary = Color(0xFF001C2A),
    background = V2.Background,
    onBackground = V2.TextPrimary,
    surface = V2.Surface,
    onSurface = V2.TextPrimary,
    surfaceVariant = V2.SurfaceHigh,
    onSurfaceVariant = V2.TextSecondary,
    outline = V2.Stroke,
    error = V2.Red,
    onError = Color(0xFF2B0000)
)

@Composable
fun V2maxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = V2Colors, typography = V2Typography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
