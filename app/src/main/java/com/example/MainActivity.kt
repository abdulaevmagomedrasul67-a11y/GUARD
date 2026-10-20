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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AliGuardUiState
import com.example.ui.AliGuardViewModel
import com.example.ui.BottomNavTab
import com.example.ui.components.LiquidGlassBackdrop
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

    if (uiState.selectedTab != BottomNavTab.HOME) {
        BackHandler {
            viewModel.selectTab(BottomNavTab.HOME)
        }
    }

    val isTunnelActive = uiState.warpAiEnabled || uiState.warpOtherEnabled

    Box(modifier = Modifier.fillMaxSize()) {
        // Clean solid minimalist background without heavy gradient orbs
        LiquidGlassBackdrop(isTunnelActive = isTunnelActive)

        // Screen content with status bar inset; bottom bar floats transparently over the bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            when (uiState.selectedTab) {
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
                    onGenerateConfig = { viewModel.generateNewWarpConfig(showSheetAfter = true) },
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

        // Floating transparent pill bottom bar (zero background underneath the bar, equal tab widths, fully rounded corners)
        LiquidGlassBottomBar(
            selectedTab = uiState.selectedTab,
            onSelectTab = viewModel::selectTab,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun LiquidGlassBottomBar(
    selectedTab: BottomNavTab,
    onSelectTab: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val glassColors = LocalLiquidGlassColors.current
    val pillShape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent) // Explicitly transparent underneath the bar
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .clip(pillShape)
                .background(
                    color = if (glassColors.isDark) {
                        Color.White.copy(alpha = 0.07f)
                    } else {
                        Color.White.copy(alpha = 0.72f)
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (glassColors.isDark) {
                        Color.White.copy(alpha = 0.16f)
                    } else {
                        Color.Black.copy(alpha = 0.08f)
                    },
                    shape = pillShape
                )
                .padding(6.dp)
                .testTag("bottom_nav_bar"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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

@Composable
private fun RowScope.BottomNavItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val glassColors = LocalLiquidGlassColors.current
    val itemShape = RoundedCornerShape(percent = 50)
    val pillBg = if (isSelected) {
        glassColors.accentCyan.copy(alpha = if (glassColors.isDark) 0.22f else 0.16f)
    } else {
        Color.Transparent
    }
    val pillBorder = if (isSelected) {
        glassColors.accentCyan.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .weight(1f) // Strictly equal width for all 3 tabs
            .minimumInteractiveComponentSize()
            .clip(itemShape)
            .background(color = pillBg, shape = itemShape)
            .border(width = 1.dp, color = pillBorder, shape = itemShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = glassColors.accentCyan),
                role = Role.Tab,
                onClick = onClick
            )
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (isSelected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = if (isSelected) glassColors.accentCyan else glassColors.textSecondary,
                modifier = Modifier.size(21.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) glassColors.textPrimary else glassColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
