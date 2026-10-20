package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.AliGuardUiState
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlassColors

@Composable
fun HomeScreen(
    uiState: AliGuardUiState,
    onToggleWarpAi: (Boolean) -> Unit,
    onToggleWarpOther: (Boolean) -> Unit,
    onGenerateConfig: () -> Unit,
    onShowConfigDialog: (Boolean) -> Unit,
    onRefreshIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassColors = LocalLiquidGlassColors.current
    val isAnyModeActive = uiState.warpAiEnabled || uiState.warpOtherEnabled
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Minimalist Brutalist Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ALI-GUARD",
                        style = MaterialTheme.typography.headlineLarge,
                        color = glassColors.textPrimary,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "CLOUDFLARE WARP • IPv4 SPLIT TUNNEL",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassColors.accentCyan
                    )
                }

                // IPv4-Only Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(glassColors.accentCyan.copy(alpha = 0.14f))
                        .border(
                            width = 1.dp,
                            color = glassColors.accentCyan.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "IPv4 ONLY",
                        style = MaterialTheme.typography.labelSmall,
                        color = glassColors.accentCyan
                    )
                }
            }

            // Big Liquid Glass Button 1: «WARP AI»
            LiquidModeToggleButton(
                title = stringResource(R.string.warp_ai_title),
                subtitle = stringResource(R.string.warp_ai_subtitle),
                badgeText = "google.com • gemini • aistudio • 1e100.net",
                icon = Icons.Filled.AutoAwesome,
                isEnabled = uiState.warpAiEnabled,
                accentColor = glassColors.accentCyan,
                testTag = "toggle_warp_ai",
                onToggle = { onToggleWarpAi(!uiState.warpAiEnabled) }
            )

            // Big Liquid Glass Button 2: «WARP Остальное»
            LiquidModeToggleButton(
                title = stringResource(R.string.warp_other_title),
                subtitle = stringResource(R.string.warp_other_subtitle),
                badgeText = "AS62041 Telegram • AS32934 WhatsApp • YouTube",
                icon = Icons.Filled.Public,
                isEnabled = uiState.warpOtherEnabled,
                accentColor = glassColors.accentViolet,
                testTag = "toggle_warp_other",
                onToggle = { onToggleWarpOther(!uiState.warpOtherEnabled) }
            )

            // Connection Status Glass Pill (Под ними — статус подключения: «Защищено» / «Отключено»)
            ConnectionStatusGlassBanner(
                isProtected = isAnyModeActive,
                isConnecting = uiState.tunnelStatus.isConnecting || uiState.isGeneratingConfig,
                isRotatingIp = uiState.tunnelStatus.isRotatingIp,
                activeRoutesCount = uiState.activeRoutesCount,
                endpoint = uiState.profile?.endpointString ?: uiState.tunnelStatus.activeEndpoint,
                reservedBytes = uiState.profile?.reservedBytes ?: listOf(0, 0, 0),
                onRefreshIp = onRefreshIp
            )

            // Native WARP Curve25519 Generator Bar
            WarpGeneratorGlassPanel(
                hasProfile = uiState.profile != null,
                isGenerating = uiState.isGeneratingConfig,
                deviceId = uiState.profile?.deviceId.orEmpty(),
                addressV4 = uiState.profile?.interfaceAddressV4 ?: "172.16.0.2",
                onGenerateClick = onGenerateConfig,
                onViewConfClick = { onShowConfigDialog(true) }
            )

            // Banner toast feedback if present
            AnimatedVisibility(
                visible = !uiState.bannerMessage.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LiquidGlassCard(
                    cornerRadius = 18.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = glassColors.accentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = uiState.bannerMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = glassColors.textPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(88.dp))
        }
    }

    if (uiState.showConfigDialog) {
        WireGuardConfDialog(
            confText = uiState.formattedConfText,
            onDismiss = { onShowConfigDialog(false) }
        )
    }
}

@Composable
private fun LiquidModeToggleButton(
    title: String,
    subtitle: String,
    badgeText: String,
    icon: ImageVector,
    isEnabled: Boolean,
    accentColor: Color,
    testTag: String,
    onToggle: () -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current
    val scale by animateFloatAsState(
        targetValue = if (isEnabled) 1.0f else 0.985f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "card_scale"
    )

    val iconBgColor by animateColorAsState(
        targetValue = if (isEnabled) accentColor.copy(alpha = 0.25f) else glassColors.textSecondary.copy(alpha = 0.12f),
        label = "icon_bg"
    )

    LiquidGlassCard(
        isActive = isEnabled,
        activeAccentColor = accentColor,
        cornerRadius = 30.dp,
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(iconBgColor)
                            .border(
                                width = 1.dp,
                                color = if (isEnabled) accentColor.copy(alpha = 0.7f) else glassColors.specularHighlightSubtle,
                                shape = RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = if (isEnabled) accentColor else glassColors.textSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = glassColors.textPrimary
                        )
                        Text(
                            text = if (isEnabled) "АКТИВЕН • ТУННЕЛЬ ВКЛЮЧЁН" else "НАЖМИТЕ ДЛЯ ВКЛЮЧЕНИЯ",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isEnabled) accentColor else glassColors.textSecondary
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accentColor,
                        uncheckedThumbColor = glassColors.textSecondary,
                        uncheckedTrackColor = Color.Transparent
                    )
                )
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = glassColors.textPrimary.copy(alpha = 0.88f)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (glassColors.isDark) Color.Black.copy(alpha = 0.25f)
                        else Color.White.copy(alpha = 0.5f)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEnabled) accentColor else glassColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusGlassBanner(
    isProtected: Boolean,
    isConnecting: Boolean,
    isRotatingIp: Boolean,
    activeRoutesCount: Int,
    endpoint: String,
    reservedBytes: List<Int>,
    onRefreshIp: () -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current
    val statusColor by animateColorAsState(
        targetValue = if (isProtected) glassColors.statusProtected else glassColors.textSecondary,
        label = "status_color"
    )

    val statusTitle = when {
        isRotatingIp -> stringResource(R.string.status_rotating_ip)
        isConnecting -> stringResource(R.string.status_connecting)
        isProtected -> stringResource(R.string.status_protected)
        else -> stringResource(R.string.status_disconnected)
    }

    LiquidGlassCard(
        isActive = isProtected,
        activeAccentColor = glassColors.statusProtected,
        cornerRadius = 26.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connection_status_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isConnecting || isRotatingIp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.2.dp,
                                color = glassColors.accentCyan
                            )
                        } else {
                            Icon(
                                imageVector = if (isProtected) Icons.Filled.CheckCircle else Icons.Outlined.Shield,
                                contentDescription = statusTitle,
                                tint = statusColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = statusTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isProtected) statusColor else glassColors.textPrimary
                        )
                        Text(
                            text = if (isProtected) {
                                "Маршрутов IPv4: $activeRoutesCount • Узел: $endpoint"
                            } else {
                                "Выберите «WARP AI» или «WARP Остальное» выше"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = glassColors.textSecondary
                        )
                    }
                }

                // Quick «Обновить IP» button right inside the status bar
                LiquidGlassCard(
                    cornerRadius = 16.dp,
                    onClick = onRefreshIp,
                    modifier = Modifier.testTag("home_refresh_ip_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.btn_refresh_ip),
                            tint = glassColors.accentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "IP",
                            style = MaterialTheme.typography.labelLarge,
                            color = glassColors.textPrimary
                        )
                    }
                }
            }

            // Reserved bytes & protocol telemetry row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Reserved: [${reservedBytes.joinToString(", ")}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = glassColors.textSecondary
                )
                Text(
                    text = "DNS: 1.1.1.1 (IPv4)",
                    style = MaterialTheme.typography.labelSmall,
                    color = glassColors.accentCyan
                )
            }
        }
    }
}

@Composable
private fun WarpGeneratorGlassPanel(
    hasProfile: Boolean,
    isGenerating: Boolean,
    deviceId: String,
    addressV4: String,
    onGenerateClick: () -> Unit,
    onViewConfClick: () -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current

    LiquidGlassCard(
        cornerRadius = 24.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = null,
                        tint = glassColors.accentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Генератор WireGuard WARP (Curve25519)",
                            style = MaterialTheme.typography.titleMedium,
                            color = glassColors.textPrimary
                        )
                        Text(
                            text = if (hasProfile) {
                                "Ключ зарегистрирован • IP: $addressV4/32 • ID: ${deviceId.take(8)}"
                            } else {
                                "Локальная генерация ключей + api.cloudflareclient.com"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = glassColors.textSecondary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiquidGlassCard(
                    isActive = true,
                    activeAccentColor = glassColors.accentCyan,
                    cornerRadius = 16.dp,
                    onClick = onGenerateClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("generate_warp_conf_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = glassColors.accentCyan
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (hasProfile) {
                                stringResource(R.string.btn_regenerate_config)
                            } else {
                                stringResource(R.string.btn_generate_config)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = glassColors.textPrimary
                        )
                    }
                }

                if (hasProfile) {
                    LiquidGlassCard(
                        cornerRadius = 16.dp,
                        onClick = onViewConfClick,
                        modifier = Modifier.testTag("view_warp_conf_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = stringResource(R.string.btn_view_config),
                                tint = glassColors.accentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = ".conf",
                                style = MaterialTheme.typography.labelLarge,
                                color = glassColors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WireGuardConfDialog(
    confText: String,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val glassColors = LocalLiquidGlassColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (glassColors.isDark) Color(0xFF0E1729) else Color(0xFFF8FAFC),
        titleContentColor = glassColors.textPrimary,
        textContentColor = glassColors.textPrimary,
        title = {
            Text(
                text = "WireGuard WARP (.conf)",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (glassColors.isDark) Color.Black.copy(alpha = 0.45f)
                            else Color(0xFFE2E8F0)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = confText,
                        style = MaterialTheme.typography.labelSmall,
                        color = glassColors.textPrimary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(confText))
                    onDismiss()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.btn_copy_config))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
