package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.AliGuardUiState
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlassColors

@Composable
fun MapScreen(
    uiState: AliGuardUiState,
    onRefreshGeoIp: () -> Unit,
    onRotateIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassColors = LocalLiquidGlassColors.current
    val isProtected = uiState.warpAiEnabled || uiState.warpOtherEnabled
    val geo = uiState.geoIpInfo
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.nav_map),
                        style = MaterialTheme.typography.headlineLarge,
                        color = glassColors.textPrimary
                    )
                    Text(
                        text = "ГЕОЛОКАЦИЯ УЗЛА CLOUDFLARE WARP (ipwho.is)",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassColors.accentCyan
                    )
                }

                LiquidGlassCard(
                    cornerRadius = 24.dp,
                    onClick = onRefreshGeoIp,
                    modifier = Modifier.testTag("map_refresh_geo_button")
                ) {
                    Box(
                        modifier = Modifier.padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.isCheckingGeoIp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = glassColors.accentCyan
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Обновить геолокацию",
                                tint = glassColors.accentCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Static zero-lag radar card
            LiquidGlassCard(
                isActive = isProtected,
                activeAccentColor = glassColors.accentCyan,
                cornerRadius = 36.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val center = Offset(w * 0.5f, h * 0.5f)
                        val maxR = size.minDimension * 0.44f
                        val gridColor = glassColors.accentCyan.copy(alpha = 0.16f)

                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                        for (i in 1..3) {
                            drawCircle(
                                color = gridColor,
                                radius = maxR * (i / 3f),
                                center = center,
                                style = Stroke(width = 1.dp.toPx(), pathEffect = dashEffect)
                            )
                        }
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, center.y),
                            end = Offset(w, center.y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dashEffect
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(center.x, 0f),
                            end = Offset(center.x, h),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dashEffect
                        )

                        val beaconColor = if (isProtected) glassColors.statusProtected else glassColors.accentCyan
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    beaconColor.copy(alpha = 0.28f),
                                    beaconColor.copy(alpha = 0f)
                                ),
                                center = center,
                                radius = maxR * 0.75f
                            ),
                            radius = maxR * 0.75f,
                            center = center
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isProtected) glassColors.statusProtected.copy(alpha = 0.22f)
                                    else glassColors.accentCyan.copy(alpha = 0.18f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NearMe,
                                contentDescription = "Локация WARP",
                                tint = if (isProtected) glassColors.statusProtected else glassColors.accentCyan,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Text(
                            text = "${geo.countryCode} • ${geo.city.ifBlank { "Frankfurt" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = glassColors.textSecondary
                        )
                    }
                }
            }

            // Primary Status Card: «Подключено к: Германия» + «Ваше подключение защищено»
            LiquidGlassCard(
                isActive = isProtected,
                activeAccentColor = glassColors.statusProtected,
                cornerRadius = 32.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("map_location_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.map_connected_to, geo.country),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = glassColors.textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = glassColors.statusProtected,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.map_connection_protected),
                            style = MaterialTheme.typography.titleMedium,
                            color = glassColors.statusProtected,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "IPv4 АДРЕС",
                                style = MaterialTheme.typography.labelSmall,
                                color = glassColors.textSecondary
                            )
                            Text(
                                text = geo.ip,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = glassColors.textPrimary
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "GOOGLE AI СТАТУС",
                                style = MaterialTheme.typography.labelSmall,
                                color = glassColors.textSecondary
                            )
                            Text(
                                text = if (geo.isGoogleAiUnlocked) "Доступ открыт" else "Требуется смена IP",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (geo.isGoogleAiUnlocked) glassColors.statusProtected else glassColors.accentCyan
                            )
                        }
                    }

                    LiquidGlassCard(
                        cornerRadius = 24.dp,
                        onClick = onRotateIp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .testTag("map_rotate_ip_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = glassColors.accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.btn_refresh_ip),
                                style = MaterialTheme.typography.labelLarge,
                                color = glassColors.textPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(104.dp))
        }
    }
}
