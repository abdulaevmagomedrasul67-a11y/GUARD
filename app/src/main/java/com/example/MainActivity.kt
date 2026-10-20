package com.example

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AliGuardUiState
import com.example.ui.AliGuardViewModel
import com.example.ui.BottomNavTab
import com.example.ui.components.LiquidGlassBackdrop
import com.example.ui.components.LiquidGlassCard
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MapScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AliGuardTheme
import com.example.ui.theme.LocalLiquidGlassColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AliGuardViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            AliGuardTheme(darkTheme = uiState.isDarkTheme) {
                AliGuardApp(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun AliGuardApp(
    uiState: AliGuardUiState,
    viewModel: AliGuardViewModel
) {
    val context = LocalContext.current
    var pendingPermissionCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingPermissionCallback?.invoke()
        } else {
            // Even in browser streaming preview or when VPN dialog is dismissed, invoke callback so user can test routing state
            pendingPermissionCallback?.invoke()
        }
        pendingPermissionCallback = null
    }

    val requestVpnPermissionIfNeeded: ((() -> Unit) -> Unit) = { onGranted ->
        val prepareIntent = try {
            VpnService.prepare(context)
        } catch (_: Exception) {
            null
        }
        if (prepareIntent != null) {
            pendingPermissionCallback = onGranted
            try {
                vpnPermissionLauncher.launch(prepareIntent)
            } catch (_: Exception) {
                onGranted()
            }
        } else {
            onGranted()
        }
    }

    // Handle system Back press on secondary tabs to return to HOME
    if (uiState.selectedTab != BottomNavTab.HOME) {
        BackHandler {
            viewModel.selectTab(BottomNavTab.HOME)
        }
    }

    val isTunnelActive = uiState.warpAiEnabled || uiState.warpOtherEnabled

    Box(modifier = Modifier.fillMaxSize()) {
        // GPU-accelerated Cloudy + Specular Liquid Glass Backdrop
        LiquidGlassBackdrop(isTunnelActive = isTunnelActive)

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                LiquidGlassBottomBar(
                    selectedTab = uiState.selectedTab,
                    onSelectTab = viewModel::selectTab
                )
            }
        ) { innerPadding ->
            Crossfade(
                targetState = uiState.selectedTab,
                animationSpec = tween(240),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                label = "tab_crossfade"
            ) { tab ->
                when (tab) {
                    BottomNavTab.HOME -> HomeScreen(
                        uiState = uiState,
                        onToggleWarpAi = { enabled ->
                            viewModel.onModeToggleRequested(
                                targetWarpAi = enabled,
                                targetWarpOther = uiState.warpOtherEnabled,
                                onRequireVpnPermission = requestVpnPermissionIfNeeded
                            )
                        },
                        onToggleWarpOther = { enabled ->
                            viewModel.onModeToggleRequested(
                                targetWarpAi = uiState.warpAiEnabled,
                                targetWarpOther = enabled,
                                onRequireVpnPermission = requestVpnPermissionIfNeeded
                            )
                        },
                        onGenerateConfig = { viewModel.generateNewWarpConfig(showSheetAfter = false) },
                        onShowConfigDialog = viewModel::setShowConfigDialog,
                        onRefreshIp = viewModel::refreshWarpIp
                    )

                    BottomNavTab.MAP -> MapScreen(
                        uiState = uiState,
                        onRefreshGeoIp = { viewModel.refreshGeoIp(silent = false) },
                        onRotateIp = viewModel::refreshWarpIp
                    )

                    BottomNavTab.SETTINGS -> SettingsScreen(
                        uiState = uiState,
                        onToggleTheme = viewModel::toggleDarkTheme,
                        onRefreshIp = viewModel::refreshWarpIp
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassBottomBar(
    selectedTab: BottomNavTab,
    onSelectTab: (BottomNavTab) -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassCard(
            cornerRadius = 30.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .testTag("bottom_nav_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    label = stringResource(R.string.nav_home),
                    selectedIcon = Icons.Filled.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    isSelected = selectedTab == BottomNavTab.HOME,
                    testTag = "nav_tab_home",
                    onClick = { onSelectTab(BottomNavTab.HOME) }
                )

                BottomNavItem(
                    label = stringResource(R.string.nav_map),
                    selectedIcon = Icons.Filled.Map,
                    unselectedIcon = Icons.Outlined.Map,
                    isSelected = selectedTab == BottomNavTab.MAP,
                    testTag = "nav_tab_map",
                    onClick = { onSelectTab(BottomNavTab.MAP) }
                )

                BottomNavItem(
                    label = stringResource(R.string.nav_settings),
                    selectedIcon = Icons.Filled.Settings,
                    unselectedIcon = Icons.Outlined.Settings,
                    isSelected = selectedTab == BottomNavTab.SETTINGS,
                    testTag = "nav_tab_settings",
                    onClick = { onSelectTab(BottomNavTab.SETTINGS) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current
    val pillBg = if (isSelected) {
        glassColors.accentCyan.copy(alpha = if (glassColors.isDark) 0.22f else 0.16f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(22.dp))
            .background(pillBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = glassColors.accentCyan),
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isSelected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = if (isSelected) glassColors.accentCyan else glassColors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) glassColors.textPrimary else glassColors.textSecondary
            )
        }
    }
}
