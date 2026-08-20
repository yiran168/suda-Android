package com.qrint.studio.ui.theme

import com.qrint.studio.ProductIdentity

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qrint.studio.model.AppThemeStyle

enum class SurfaceTreatment { GLASS, PAPER, EDITORIAL, GRID, POP, NEON, FROST, LIQUID, SMOKE, PRISM }

/** The home composition changes with the theme; these are layout systems, not color presets. */
enum class HomeLayout { BENTO, JOURNAL, EDITORIAL, LAB, BUBBLES, TERMINAL, FROST_DECK, LIQUID_RAIL, SMOKE_CONSOLE, PRISM_MOSAIC }

@Immutable
data class QrintVisualTokens(
    val treatment: SurfaceTreatment,
    val homeLayout: HomeLayout,
    val heroColors: List<Color>,
    val tileElevation: Int,
    val outlinedTiles: Boolean,
    val iconCornerDp: Int,
    val decorativeLabel: String,
)

val LocalQrintVisuals = staticCompositionLocalOf {
    QrintVisualTokens(
        SurfaceTreatment.GLASS,
        HomeLayout.BENTO,
        listOf(Color(0xFF2146DA), Color(0xFF7559FF), Color(0xFF00A88A)),
        1,
        false,
        18,
        "LINGYIN / FLOW",
    )
}

private val AuroraColors = lightColorScheme(
    primary = Color(0xFF315DFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF), onPrimaryContainer = Color(0xFF0A277A),
    secondary = Color(0xFF00A88A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9F3E7), onSecondaryContainer = Color(0xFF003C32),
    tertiary = Color(0xFFFF6B62), tertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF8F8FE), surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE8EAF2), outline = Color(0xFF777986),
)

private val PaperColors = lightColorScheme(
    primary = Color(0xFF75543F), onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DDC9), onPrimaryContainer = Color(0xFF382516),
    secondary = Color(0xFF9B6A24), secondaryContainer = Color(0xFFFFDEA7),
    tertiary = Color(0xFF55715A), tertiaryContainer = Color(0xFFD7ECD5),
    background = Color(0xFFF8F1E7), surface = Color(0xFFFFFBF4),
    surfaceVariant = Color(0xFFEDE2D4), outline = Color(0xFF817568),
)

private val InkColors = lightColorScheme(
    primary = Color(0xFF111111), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8), onPrimaryContainer = Color.Black,
    secondary = Color(0xFFCA2F35), secondaryContainer = Color(0xFFFFDADB),
    tertiary = Color(0xFF4D5B73), tertiaryContainer = Color(0xFFD9E2FA),
    background = Color(0xFFF4F4F1), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6E6E1), outline = Color(0xFF555555),
)

private val MintColors = lightColorScheme(
    primary = Color(0xFF007E72), onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1E8), onPrimaryContainer = Color(0xFF003731),
    secondary = Color(0xFF3E5E98), secondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF8B5C00), tertiaryContainer = Color(0xFFFFDDA3),
    background = Color(0xFFF1FAF7), surface = Color(0xFFFBFFFD),
    surfaceVariant = Color(0xFFDDEDEA), outline = Color(0xFF617A75),
)

private val SunsetColors = lightColorScheme(
    primary = Color(0xFFE7523E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2), onPrimaryContainer = Color(0xFF5D160A),
    secondary = Color(0xFF9D3D79), secondaryContainer = Color(0xFFFFD8EC),
    tertiary = Color(0xFF6E5ACF), tertiaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFFFFF7F5), surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF5E3E0), outline = Color(0xFF8C706C),
)

private val NeonColors = darkColorScheme(
    primary = Color(0xFF55F4E2), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005049), onPrimaryContainer = Color(0xFF8FFFF0),
    secondary = Color(0xFFB9A4FF), secondaryContainer = Color(0xFF493B86),
    tertiary = Color(0xFFFF6FAE), tertiaryContainer = Color(0xFF7A2750),
    background = Color(0xFF090B13), surface = Color(0xFF111521),
    surfaceVariant = Color(0xFF252A39), outline = Color(0xFF8991A7),
)

private val FrostGlassColors = lightColorScheme(
    primary = Color(0xFF176B8F), onPrimary = Color.White,
    primaryContainer = Color(0xBFD8F3FF), onPrimaryContainer = Color(0xFF003549),
    secondary = Color(0xFF5576A8), secondaryContainer = Color(0xBFDCE7FF),
    tertiary = Color(0xFF2E8C88), tertiaryContainer = Color(0xBFC8F1ED),
    background = Color(0xDDEBF7FF), surface = Color(0xCCFFFFFF),
    surfaceContainerLow = Color(0xA6FFFFFF), surfaceContainer = Color(0xB8FFFFFF),
    surfaceContainerHigh = Color(0xC9FFFFFF),
    surfaceVariant = Color(0xBFDCEAF2), outline = Color(0xFF78909C),
)

private val LiquidGlassColors = lightColorScheme(
    primary = Color(0xFF3156C8), onPrimary = Color.White,
    primaryContainer = Color(0xBFDCE4FF), onPrimaryContainer = Color(0xFF102461),
    secondary = Color(0xFF7356C6), secondaryContainer = Color(0xBFE9DDFF),
    tertiary = Color(0xFF008E9B), tertiaryContainer = Color(0xBFC8F1F5),
    background = Color(0xDDEEF1FF), surface = Color(0xCFFBFCFF),
    surfaceContainerLow = Color(0x91FFFFFF), surfaceContainer = Color(0xA8FFFFFF),
    surfaceContainerHigh = Color(0xBDFFFFFF),
    surfaceVariant = Color(0xBFE3E7F5), outline = Color(0xFF747B98),
)

private val SmokeGlassColors = darkColorScheme(
    primary = Color(0xFFAFC9FF), onPrimary = Color(0xFF0B2448),
    primaryContainer = Color(0xA9364B67), onPrimaryContainer = Color(0xFFE5EEFF),
    secondary = Color(0xFFD8C3A5), secondaryContainer = Color(0xA94D4439),
    tertiary = Color(0xFFA8D8D3), tertiaryContainer = Color(0xA92D4D4B),
    background = Color(0xE1151A22), surface = Color(0xB8232933),
    surfaceContainerLow = Color(0x80232933), surfaceContainer = Color(0x992A313D),
    surfaceContainerHigh = Color(0xB2343C49),
    surfaceVariant = Color(0xA9363D48), outline = Color(0xFF929AA8),
)

private val PrismGlassColors = lightColorScheme(
    primary = Color(0xFF6D45C5), onPrimary = Color.White,
    primaryContainer = Color(0xBFE9DEFF), onPrimaryContainer = Color(0xFF2B1268),
    secondary = Color(0xFFB64174), secondaryContainer = Color(0xBFFFF0F6),
    tertiary = Color(0xFF007F88), tertiaryContainer = Color(0xBFC5F1F3),
    background = Color(0xDFFFF7FE), surface = Color(0xCFFFFFFF),
    surfaceContainerLow = Color(0x8FFFFFFF), surfaceContainer = Color(0xA8FFF9FF),
    surfaceContainerHigh = Color(0xC2FFFFFF),
    surfaceVariant = Color(0xBFF3E6F4), outline = Color(0xFF88758C),
)

private fun typography(style: AppThemeStyle): Typography {
    val displayFamily = when (style) {
        AppThemeStyle.PAPER -> FontFamily.Serif
        AppThemeStyle.NEON, AppThemeStyle.SMOKE_GLASS -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val bodyFamily = if (style == AppThemeStyle.NEON || style == AppThemeStyle.SMOKE_GLASS) FontFamily.Monospace else FontFamily.SansSerif
    return Typography(
        headlineLarge = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.ExtraBold, fontSize = if (style == AppThemeStyle.INK) 34.sp else 32.sp),
        headlineMedium = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp),
        headlineSmall = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.Bold, fontSize = 23.sp),
        titleLarge = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.Bold, fontSize = 21.sp),
        titleMedium = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        labelLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    )
}

private fun shapes(style: AppThemeStyle): Shapes = when (style) {
    AppThemeStyle.AURORA -> Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(15.dp),
        medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(34.dp),
    )
    AppThemeStyle.PAPER -> Shapes(
        extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(5.dp),
        medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
    )
    AppThemeStyle.INK -> Shapes(
        extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(1.dp),
        medium = RoundedCornerShape(3.dp), large = RoundedCornerShape(5.dp), extraLarge = RoundedCornerShape(7.dp),
    )
    AppThemeStyle.MINT -> Shapes(
        extraSmall = CutCornerShape(5.dp), small = CutCornerShape(8.dp),
        medium = CutCornerShape(12.dp), large = CutCornerShape(16.dp), extraLarge = CutCornerShape(22.dp),
    )
    AppThemeStyle.SUNSET -> Shapes(
        extraSmall = RoundedCornerShape(18.dp), small = RoundedCornerShape(22.dp),
        medium = RoundedCornerShape(28.dp), large = RoundedCornerShape(36.dp), extraLarge = RoundedCornerShape(48.dp),
    )
    AppThemeStyle.NEON -> Shapes(
        extraSmall = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        small = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
        medium = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp),
        large = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
        extraLarge = CutCornerShape(topStart = 24.dp, bottomEnd = 24.dp),
    )
    AppThemeStyle.FROST_GLASS -> Shapes(
        extraSmall = RoundedCornerShape(9.dp), small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
    )
    AppThemeStyle.LIQUID_GLASS -> Shapes(
        extraSmall = RoundedCornerShape(18.dp), small = RoundedCornerShape(24.dp),
        medium = RoundedCornerShape(30.dp), large = RoundedCornerShape(40.dp), extraLarge = RoundedCornerShape(54.dp),
    )
    AppThemeStyle.SMOKE_GLASS -> Shapes(
        extraSmall = RoundedCornerShape(5.dp), small = RoundedCornerShape(9.dp),
        medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(26.dp),
    )
    AppThemeStyle.PRISM_GLASS -> Shapes(
        extraSmall = CutCornerShape(topStart = 5.dp, bottomEnd = 8.dp),
        small = CutCornerShape(topStart = 8.dp, bottomEnd = 12.dp),
        medium = CutCornerShape(topStart = 12.dp, bottomEnd = 18.dp),
        large = CutCornerShape(topStart = 17.dp, bottomEnd = 24.dp),
        extraLarge = CutCornerShape(topStart = 24.dp, bottomEnd = 32.dp),
    )
}

private fun visuals(style: AppThemeStyle): QrintVisualTokens = when (style) {
    AppThemeStyle.AURORA -> QrintVisualTokens(SurfaceTreatment.GLASS, HomeLayout.BENTO, listOf(Color(0xFF2146DA), Color(0xFF7559FF), Color(0xFF00A88A)), 2, false, 18, "LINGYIN / FLOW")
        AppThemeStyle.PAPER -> QrintVisualTokens(SurfaceTreatment.PAPER, HomeLayout.JOURNAL, listOf(Color(0xFF6B4936), Color(0xFFA2723F)), 0, true, 5, "${ProductIdentity.NAME}手账 · 今日")
        AppThemeStyle.INK -> QrintVisualTokens(SurfaceTreatment.EDITORIAL, HomeLayout.EDITORIAL, listOf(Color(0xFF101010), Color(0xFF343434)), 0, true, 2, "${ProductIdentity.NAME}编辑部 / 03")
        AppThemeStyle.MINT -> QrintVisualTokens(SurfaceTreatment.GRID, HomeLayout.LAB, listOf(Color(0xFF006C62), Color(0xFF339A8D), Color(0xFF3E5E98)), 1, true, 8, "${ProductIdentity.NAME}实验室 / READY")
    AppThemeStyle.SUNSET -> QrintVisualTokens(SurfaceTreatment.POP, HomeLayout.BUBBLES, listOf(Color(0xFFE7523E), Color(0xFFE96788), Color(0xFF765BD8)), 3, false, 24, "灵感气泡 / POP")
    AppThemeStyle.NEON -> QrintVisualTokens(SurfaceTreatment.NEON, HomeLayout.TERMINAL, listOf(Color(0xFF172137), Color(0xFF482B78), Color(0xFF005C58)), 0, true, 4, "LINGYIN://LOCAL")
        AppThemeStyle.FROST_GLASS -> QrintVisualTokens(SurfaceTreatment.FROST, HomeLayout.FROST_DECK, listOf(Color(0xCC5FB9DA), Color(0xCC8FAFE8), Color(0xCC72C7BE)), 1, true, 20, "${ProductIdentity.NAME} / FROST")
        AppThemeStyle.LIQUID_GLASS -> QrintVisualTokens(SurfaceTreatment.LIQUID, HomeLayout.LIQUID_RAIL, listOf(Color(0xD9366DE0), Color(0xD97D55D9), Color(0xD900A9B2)), 3, false, 28, "${ProductIdentity.NAME} / LIQUID")
    AppThemeStyle.SMOKE_GLASS -> QrintVisualTokens(SurfaceTreatment.SMOKE, HomeLayout.SMOKE_CONSOLE, listOf(Color(0xD926303D), Color(0xD94A4657), Color(0xD9234A4A)), 0, true, 10, "LINGYIN://SMOKE")
        AppThemeStyle.PRISM_GLASS -> QrintVisualTokens(SurfaceTreatment.PRISM, HomeLayout.PRISM_MOSAIC, listOf(Color(0xD97C5FE8), Color(0xD9E45E9B), Color(0xD929B8BE)), 2, true, 14, "${ProductIdentity.NAME} / PRISM")
}

@Composable
fun QrintTheme(style: AppThemeStyle = AppThemeStyle.AURORA, content: @Composable () -> Unit) {
    val colors = when (style) {
        AppThemeStyle.AURORA -> AuroraColors
        AppThemeStyle.PAPER -> PaperColors
        AppThemeStyle.INK -> InkColors
        AppThemeStyle.MINT -> MintColors
        AppThemeStyle.SUNSET -> SunsetColors
        AppThemeStyle.NEON -> NeonColors
        AppThemeStyle.FROST_GLASS -> FrostGlassColors
        AppThemeStyle.LIQUID_GLASS -> LiquidGlassColors
        AppThemeStyle.SMOKE_GLASS -> SmokeGlassColors
        AppThemeStyle.PRISM_GLASS -> PrismGlassColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalQrintVisuals provides visuals(style)) {
        MaterialTheme(colorScheme = colors, typography = typography(style), shapes = shapes(style)) {
            Box(Modifier.fillMaxSize().background(backgroundBrush(style))) {
                ThemeBackdrop(style)
                content()
            }
        }
    }
}

/**
 * Four glass themes share one rendering path while keeping a genuinely different material
 * language. Semi-transparent Material surfaces above this backdrop reveal the light field,
 * liquid blobs, smoke plumes or prism bands without relying on Android-version-specific blur.
 */
@Composable
private fun ThemeBackdrop(style: AppThemeStyle) {
    if (style !in setOf(
            AppThemeStyle.FROST_GLASS,
            AppThemeStyle.LIQUID_GLASS,
            AppThemeStyle.SMOKE_GLASS,
            AppThemeStyle.PRISM_GLASS,
        )
    ) return

    // One slow, low-amplitude phase keeps the glass alive without moving controls or burning
    // through frames on lower-end devices. Each material interprets the phase differently.
    val motion = rememberInfiniteTransition(label = "glass-material")
    val phase = motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glass-phase",
    ).value

    Canvas(Modifier.fillMaxSize()) {
        when (style) {
            AppThemeStyle.FROST_GLASS -> {
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x99FFFFFF), Color.Transparent)),
                    radius = size.minDimension * 0.46f,
                    center = Offset(size.width * (0.12f + phase * 0.018f), size.height * (0.16f - phase * 0.012f)),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x667ED7F5), Color.Transparent)),
                    radius = size.minDimension * 0.4f,
                    center = Offset(size.width * (0.88f - phase * 0.014f), size.height * (0.72f + phase * 0.016f)),
                )
                val step = size.width.coerceAtMost(size.height) / 12f
                var x = step
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.16f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
            }
            AppThemeStyle.LIQUID_GLASS -> {
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x806580F2), Color.Transparent)),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * (0.15f + phase * 0.035f), size.height * (0.26f + phase * 0.016f)),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x806EDDD3), Color.Transparent)),
                    radius = size.minDimension * 0.52f,
                    center = Offset(size.width * (0.9f - phase * 0.03f), size.height * (0.58f - phase * 0.02f)),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x70E08BDF), Color.Transparent)),
                    radius = size.minDimension * 0.35f,
                    center = Offset(size.width * (0.52f + phase * 0.022f), size.height * (0.92f - phase * 0.018f)),
                )
            }
            AppThemeStyle.SMOKE_GLASS -> {
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x704E6681), Color.Transparent)),
                    radius = size.minDimension * 0.62f,
                    center = Offset(size.width * (0.18f + phase * 0.018f), size.height * (0.2f + phase * 0.012f)),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x506C625A), Color.Transparent)),
                    radius = size.minDimension * 0.58f,
                    center = Offset(size.width * (0.92f - phase * 0.012f), size.height * (0.74f - phase * 0.018f)),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.11f),
                    start = Offset(0f, size.height * (0.34f + phase * 0.01f)),
                    end = Offset(size.width, size.height * (0.27f - phase * 0.01f)),
                    strokeWidth = 2f,
                )
            }
            AppThemeStyle.PRISM_GLASS -> {
                rotate(17f + phase * 3f, pivot = Offset(size.width * 0.5f, size.height * 0.5f)) {
                    drawRect(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, Color(0x55FF5F9B), Color(0x555E78FF), Color(0x553DDCC5), Color.Transparent),
                        ),
                        topLeft = Offset(-size.width * 0.25f, size.height * 0.12f),
                        size = Size(size.width * 1.5f, size.height * 0.22f),
                    )
                    drawRect(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, Color(0x44FFD06A), Color(0x447A63FF), Color.Transparent),
                        ),
                        topLeft = Offset(-size.width * 0.2f, size.height * 0.64f),
                        size = Size(size.width * 1.4f, size.height * 0.16f),
                    )
                }
            }
            else -> Unit
        }
    }
}

private fun backgroundBrush(style: AppThemeStyle): Brush = when (style) {
    AppThemeStyle.FROST_GLASS -> Brush.linearGradient(listOf(Color(0xFFE9F8FF), Color(0xFFDDE9FF), Color(0xFFE4FBF7)))
    AppThemeStyle.LIQUID_GLASS -> Brush.linearGradient(listOf(Color(0xFFD8E7FF), Color(0xFFE8DCFF), Color(0xFFD3F5F3)))
    AppThemeStyle.SMOKE_GLASS -> Brush.linearGradient(listOf(Color(0xFF0B0F15), Color(0xFF29313D), Color(0xFF16191F)))
    AppThemeStyle.PRISM_GLASS -> Brush.linearGradient(listOf(Color(0xFFFFE4F1), Color(0xFFE1E8FF), Color(0xFFD7FAF5), Color(0xFFFFF0DA)))
    AppThemeStyle.NEON -> Brush.linearGradient(listOf(Color(0xFF090B13), Color(0xFF141128), Color(0xFF071E21)))
    else -> Brush.linearGradient(listOf(colorsForBackground(style), colorsForBackground(style)))
}

private fun colorsForBackground(style: AppThemeStyle): Color = when (style) {
    AppThemeStyle.AURORA -> Color(0xFFF8F8FE)
    AppThemeStyle.PAPER -> Color(0xFFF8F1E7)
    AppThemeStyle.INK -> Color(0xFFF4F4F1)
    AppThemeStyle.MINT -> Color(0xFFF1FAF7)
    AppThemeStyle.SUNSET -> Color(0xFFFFF7F5)
    AppThemeStyle.NEON -> Color(0xFF090B13)
    AppThemeStyle.FROST_GLASS -> Color(0xFFE9F8FF)
    AppThemeStyle.LIQUID_GLASS -> Color(0xFFD8E7FF)
    AppThemeStyle.SMOKE_GLASS -> Color(0xFF0B0F15)
    AppThemeStyle.PRISM_GLASS -> Color(0xFFFFE4F1)
}
