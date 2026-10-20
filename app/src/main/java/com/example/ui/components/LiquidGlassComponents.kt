package com.example.ui.components

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalLiquidGlassColors

/**
 * Clean, zero-lag minimalist background without heavy gradient animations or per-frame shaders.
 * Designed for maximum 120fps performance on both low-end and flagship devices.
 */
@Composable
fun LiquidGlassBackdrop(
    isTunnelActive: Boolean,
    modifier: Modifier = Modifier
) {
    val glassColors = LocalLiquidGlassColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(glassColors.canvasBackground)
    )
}

/**
 * Lightweight, high-performance iOS-style glass container with rounded corners,
 * subtle translucent fill, and crisp specular border. No per-frame recomposition or blur overhead.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeAccentColor: Color? = null,
    cornerRadius: Dp = 32.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val accent = activeAccentColor ?: glassColors.accentCyan

    val topFill = if (isActive) {
        if (glassColors.isDark) accent.copy(alpha = 0.20f) else accent.copy(alpha = 0.16f)
    } else {
        glassColors.glassFillTop
    }

    val bottomFill = if (isActive) {
        if (glassColors.isDark) accent.copy(alpha = 0.08f) else accent.copy(alpha = 0.08f)
    } else {
        glassColors.glassFillBottom
    }

    val borderStart = if (isActive) {
        accent.copy(alpha = 0.85f)
    } else {
        glassColors.specularHighlightBright
    }

    val borderEnd = if (isActive) {
        accent.copy(alpha = 0.35f)
    } else {
        glassColors.specularHighlightSubtle
    }

    val fillBrush = remember(topFill, bottomFill) {
        Brush.verticalGradient(colors = listOf(topFill, bottomFill))
    }

    val borderBrush = remember(borderStart, borderEnd) {
        Brush.linearGradient(
            colors = listOf(borderStart, borderEnd),
            start = Offset(0f, 0f),
            end = Offset(500f, 500f)
        )
    }

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
            .background(brush = fillBrush, shape = shape)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = shape
            )
            .then(clickableModifier),
        content = content
    )
}
