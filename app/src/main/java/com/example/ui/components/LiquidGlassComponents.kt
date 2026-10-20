package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalLiquidGlassColors
import com.skydoves.cloudy.cloudy
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated ambient Liquid Glass background with flowing refractive orbs and
 * GPU-accelerated `skydoves/Cloudy` backdrop diffusion + subtle noise grain.
 */
@Composable
fun LiquidGlassBackdrop(
    isTunnelActive: Boolean,
    modifier: Modifier = Modifier
) {
    val glassColors = LocalLiquidGlassColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_orbs")

    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase"
    )

    val activeBoost by animateFloatAsState(
        targetValue = if (isTunnelActive) 1.25f else 0.85f,
        animationSpec = tween(700),
        label = "active_boost"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glassColors.canvasBackground)
    ) {
        // Layer 1: High-contrast refractive color caustics blurred via skydoves/Cloudy
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .cloudy(radius = 24)
        ) {
            val w = size.width
            val h = size.height
            val minDim = size.minDimension

            val orb1Center = Offset(
                x = w * (0.25f + 0.18f * cos(phase)),
                y = h * (0.22f + 0.12f * sin(phase))
            )
            val orb2Center = Offset(
                x = w * (0.78f + 0.15f * sin(phase * 0.8f)),
                y = h * (0.48f + 0.15f * cos(phase * 0.8f))
            )
            val orb3Center = Offset(
                x = w * (0.35f + 0.2f * sin(phase + 1.4f)),
                y = h * (0.80f + 0.1f * cos(phase + 1.4f))
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glassColors.orbPrimary.copy(alpha = 0.50f * activeBoost.coerceAtMost(1f)),
                        Color.Transparent
                    ),
                    center = orb1Center,
                    radius = minDim * 0.72f
                ),
                radius = minDim * 0.72f,
                center = orb1Center
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glassColors.orbSecondary.copy(alpha = 0.44f * activeBoost.coerceAtMost(1f)),
                        Color.Transparent
                    ),
                    center = orb2Center,
                    radius = minDim * 0.68f
                ),
                radius = minDim * 0.68f,
                center = orb2Center
            )

            if (isTunnelActive) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glassColors.orbTertiary.copy(alpha = 0.42f),
                            Color.Transparent
                        ),
                        center = orb3Center,
                        radius = minDim * 0.64f
                    ),
                    radius = minDim * 0.64f,
                    center = orb3Center
                )
            }
        }

        // Layer 2: Subtle procedural tactile noise & optical grid lines for iOS Liquid Glass depth
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val noiseAlpha = if (glassColors.isDark) 0.025f else 0.018f
            val noiseColor = if (glassColors.isDark) Color.White.copy(alpha = noiseAlpha) else Color.Black.copy(alpha = noiseAlpha)

            // Deterministic pseudo-random micro-grain points
            var seed = 1337
            val step = 28f
            var y = 8f
            while (y < h) {
                var x = 8f
                while (x < w) {
                    seed = (seed * 1103515245 + 12345) and 0x7fffffff
                    val jitterX = (seed % 19) - 9
                    val jitterY = ((seed shr 5) % 19) - 9
                    drawCircle(
                        color = noiseColor,
                        radius = 1.1f,
                        center = Offset(x + jitterX, y + jitterY)
                    )
                    x += step
                }
                y += step
            }
        }
    }
}

/**
 * Reusable iOS-style Liquid Glass container featuring:
 * - Translucent refractive fill (adapts to Dark/Light theme and Active state)
 * - Diagonal Specular & Chromatic rim border (top-left bright highlight -> chromatic middle -> subtle bottom-right)
 * - Inner top specular sheen curve
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeAccentColor: Color? = null,
    cornerRadius: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val accent = activeAccentColor ?: glassColors.accentCyan

    val topFill by animateColorAsState(
        targetValue = if (isActive) {
            if (glassColors.isDark) accent.copy(alpha = 0.28f) else accent.copy(alpha = 0.20f)
        } else {
            glassColors.glassFillTop
        },
        animationSpec = tween(350),
        label = "glass_top_fill"
    )

    val bottomFill by animateColorAsState(
        targetValue = if (isActive) {
            if (glassColors.isDark) glassColors.accentViolet.copy(alpha = 0.14f) else glassColors.glassActiveFillBottom
        } else {
            glassColors.glassFillBottom
        },
        animationSpec = tween(350),
        label = "glass_bottom_fill"
    )

    val borderTopLeft by animateColorAsState(
        targetValue = if (isActive) accent.copy(alpha = 0.95f) else glassColors.specularHighlightBright,
        animationSpec = tween(350),
        label = "border_tl"
    )

    val borderMid by animateColorAsState(
        targetValue = if (isActive) glassColors.chromaticEdge else glassColors.specularHighlightSubtle,
        animationSpec = tween(350),
        label = "border_mid"
    )

    val borderBottomRight by animateColorAsState(
        targetValue = if (isActive) accent.copy(alpha = 0.55f) else glassColors.specularHighlightSubtle,
        animationSpec = tween(350),
        label = "border_br"
    )

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = accent),
            role = Role.Button,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(topFill, bottomFill),
                    start = Offset(0f, 0f),
                    end = Offset(600f, 800f)
                )
            )
            .border(
                width = if (isActive) 1.6.dp else 1.1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(borderTopLeft, borderMid, borderBottomRight),
                    start = Offset(0f, 0f),
                    end = Offset(700f, 700f)
                ),
                shape = shape
            )
            .drawWithCache {
                val radiusPx = cornerRadius.toPx()
                val sheenColor = if (glassColors.isDark) {
                    Color.White.copy(alpha = if (isActive) 0.14f else 0.08f)
                } else {
                    Color.White.copy(alpha = 0.55f)
                }
                onDrawWithContent {
                    drawContent()
                    // Specular top inner rim reflection
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(sheenColor, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.35f
                        ),
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
            .then(clickableModifier),
        content = content
    )
}
