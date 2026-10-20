package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.AliGuardUiState
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.LocalLiquidGlassColors

/**
 * Settings screen adhering strictly to the prompt's minimalist requirement:
 * 1. Dark / Light theme switch.
 * 2. Optional "Обновить IP" button (WARP reconnect to change region).
 * 3. "О приложении" item specifying Developer: Абдулаев Али.
 * No extra nested menus or unnecessary settings items.
 */
@Composable
fun SettingsScreen(
    uiState: AliGuardUiState,
    onToggleTheme: (Boolean) -> Unit,
    onRefreshIp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassColors = LocalLiquidGlassColors.current
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
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineLarge,
                    color = glassColors.textPrimary
                )
                Text(
                    text = "МИНИМАЛИСТИЧНОЕ УПРАВЛЕНИЕ",
                    style = MaterialTheme.typography.labelSmall,
                    color = glassColors.accentCyan
                )
            }

            // 1. Переключатель тёмной/светлой темы
            LiquidGlassCard(
                cornerRadius = 26.dp,
                onClick = { onToggleTheme(!uiState.isDarkTheme) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_theme_toggle_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
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
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(glassColors.accentCyan.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (uiState.isDarkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                                contentDescription = stringResource(R.string.settings_theme_title),
                                tint = glassColors.accentCyan,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.settings_theme_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = glassColors.textPrimary
                            )
                            Text(
                                text = if (uiState.isDarkTheme) {
                                    stringResource(R.string.settings_theme_dark)
                                } else {
                                    stringResource(R.string.settings_theme_light)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = glassColors.textSecondary
                            )
                        }
                    }

                    Switch(
                        checked = uiState.isDarkTheme,
                        onCheckedChange = onToggleTheme,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = glassColors.accentCyan,
                            uncheckedThumbColor = glassColors.textSecondary,
                            uncheckedTrackColor = Color.Transparent
                        )
                    )
                }
            }

            // 2. Кнопка «Обновить IP» (переподключение WARP для смены региона)
            LiquidGlassCard(
                isActive = true,
                activeAccentColor = glassColors.accentCyan,
                cornerRadius = 26.dp,
                onClick = onRefreshIp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_refresh_ip_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
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
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(glassColors.accentCyan.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (uiState.tunnelStatus.isRotatingIp || uiState.isCheckingGeoIp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.2.dp,
                                    color = glassColors.accentCyan
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.settings_refresh_ip_title),
                                    tint = glassColors.accentCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = stringResource(R.string.settings_refresh_ip_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = glassColors.textPrimary
                            )
                            Text(
                                text = stringResource(R.string.settings_refresh_ip_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = glassColors.textSecondary
                            )
                        }
                    }
                }
            }

            // 3. Пункт «О приложении»: указывает разработчика — Абдулаев Али
            LiquidGlassCard(
                cornerRadius = 26.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_about_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassColors.accentViolet.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.settings_about_title),
                            tint = glassColors.accentViolet,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.settings_about_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = glassColors.textPrimary
                        )
                        Text(
                            text = stringResource(R.string.settings_developer_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = glassColors.accentCyan
                        )
                        Text(
                            text = stringResource(R.string.settings_about_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = glassColors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}
