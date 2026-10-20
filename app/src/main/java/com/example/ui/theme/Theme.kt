package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class LiquidGlassColors(
    val isDark: Boolean,
    val canvasBackground: Color,
    val orbPrimary: Color,
    val orbSecondary: Color,
    val orbTertiary: Color,
    val glassFillTop: Color,
    val glassFillBottom: Color,
    val glassActiveFillTop: Color,
    val glassActiveFillBottom: Color,
    val specularHighlightBright: Color,
    val specularHighlightSubtle: Color,
    val chromaticEdge: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentCyan: Color,
    val accentViolet: Color,
    val statusProtected: Color
)

val LocalLiquidGlassColors = staticCompositionLocalOf {
    LiquidGlassColors(
        isDark = true,
        canvasBackground = ObsidianDeep,
        orbPrimary = Color(0xFF0284C7),
        orbSecondary = Color(0xFF4F46E5),
        orbTertiary = Color(0xFF0D9488),
        glassFillTop = Color(0x38FFFFFF),
        glassFillBottom = Color(0x14FFFFFF),
        glassActiveFillTop = Color(0x5238BDF8),
        glassActiveFillBottom = Color(0x246366F1),
        specularHighlightBright = Color(0x99FFFFFF),
        specularHighlightSubtle = Color(0x1AFFFFFF),
        chromaticEdge = Color(0x6638BDF8),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        accentCyan = ElectricCyan,
        accentViolet = ChromaticViolet,
        statusProtected = EmeraldProtected
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    secondary = ChromaticViolet,
    tertiary = EmeraldProtected,
    background = ObsidianDeep,
    surface = ObsidianSurface,
    onPrimary = Color(0xFF041322),
    onSecondary = Color.White,
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC)
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    secondary = IndigoAccent,
    tertiary = EmeraldLightProtected,
    background = FrostBackground,
    surface = FrostSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = SlateTextPrimary,
    onSurface = SlateTextPrimary
)

@Composable
fun AliGuardTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val glassColors = if (darkTheme) {
        LiquidGlassColors(
            isDark = true,
            canvasBackground = ObsidianDeep,
            orbPrimary = Color(0xFF0369A1),
            orbSecondary = Color(0xFF4338CA),
            orbTertiary = Color(0xFF0F766E),
            glassFillTop = Color(0x26FFFFFF),
            glassFillBottom = Color(0x0DFFFFFF),
            glassActiveFillTop = Color(0x4538BDF8),
            glassActiveFillBottom = Color(0x22818CF8),
            specularHighlightBright = Color(0x88FFFFFF),
            specularHighlightSubtle = Color(0x18FFFFFF),
            chromaticEdge = Color(0x8038BDF8),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF94A3B8),
            accentCyan = ElectricCyan,
            accentViolet = ChromaticViolet,
            statusProtected = EmeraldProtected
        )
    } else {
        LiquidGlassColors(
            isDark = false,
            canvasBackground = FrostBackground,
            orbPrimary = Color(0xFF7DD3FC),
            orbSecondary = Color(0xFFA5B4FC),
            orbTertiary = Color(0xFF6EE7B7),
            glassFillTop = Color(0xCCFFFFFF),
            glassFillBottom = Color(0x88FFFFFF),
            glassActiveFillTop = Color(0xE6E0F2FE),
            glassActiveFillBottom = Color(0xCCDBEAFE),
            specularHighlightBright = Color(0xFFFFFFFF),
            specularHighlightSubtle = Color(0x66FFFFFF),
            chromaticEdge = Color(0x990284C7),
            textPrimary = SlateTextPrimary,
            textSecondary = SlateTextSecondary,
            accentCyan = CobaltPrimary,
            accentViolet = IndigoAccent,
            statusProtected = EmeraldLightProtected
        )
    }

    CompositionLocalProvider(LocalLiquidGlassColors provides glassColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    AliGuardTheme(darkTheme = darkTheme, content = content)
}
