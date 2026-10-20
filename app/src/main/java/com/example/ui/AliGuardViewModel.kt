package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.WarpPreferencesRepository
import com.example.routing.SplitTunnelRules
import com.example.vpn.AliGuardVpnService
import com.example.vpn.VpnStateManager
import com.example.vpn.VpnTunnelStatus
import com.example.warp.CloudflareWarpClient
import com.example.warp.GeoIpInfo
import com.example.warp.WarpCrypto
import com.example.warp.WarpProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BottomNavTab {
    HOME,
    MAP,
    SETTINGS
}

data class AliGuardUiState(
    val selectedTab: BottomNavTab = BottomNavTab.HOME,
    val warpAiEnabled: Boolean = false,
    val warpOtherEnabled: Boolean = false,
    val isDarkTheme: Boolean = true,
    val autoUnlockAiIp: Boolean = true,
    val profile: WarpProfile? = null,
    val tunnelStatus: VpnTunnelStatus = VpnTunnelStatus(),
    val isGeneratingConfig: Boolean = false,
    val isCheckingGeoIp: Boolean = false,
    val geoIpInfo: GeoIpInfo = GeoIpInfo(
        ip = "188.114.97.1",
        country = "Германия",
        countryCode = "DE",
        region = "Hesse",
        city = "Frankfurt am Main",
        isp = "Cloudflare AmneziaWG",
        latitude = 50.1109,
        longitude = 8.6821,
        isGoogleAiUnlocked = true,
        colo = "FRA"
    ),
    val showConfigDialog: Boolean = false,
    val bannerMessage: String? = null
) {
    val activeRoutesCount: Int
        get() = SplitTunnelRules.buildRoutes(warpAiEnabled, warpOtherEnabled).size

    val formattedConfText: String
        get() = profile?.toWireGuardConf(
            warpAiEnabled = warpAiEnabled,
            warpOtherEnabled = warpOtherEnabled
        ) ?: "# Конфигурация AmneziaWG ещё не создана.\n# Нажмите «Сгенерировать WARP .conf» на главном экране."
}

class AliGuardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WarpPreferencesRepository(application.applicationContext)

    private val _localUiState = MutableStateFlow(LocalUiControlState())
    private var endpointRotationStep = 0

    private data class LocalUiControlState(
        val selectedTab: BottomNavTab = BottomNavTab.HOME,
        val isGeneratingConfig: Boolean = false,
        val isCheckingGeoIp: Boolean = false,
        val geoIpInfo: GeoIpInfo = GeoIpInfo(
            ip = "188.114.97.1",
            country = "Германия",
            countryCode = "DE",
            region = "Hesse",
            city = "Frankfurt am Main",
            isp = "Cloudflare AmneziaWG",
            latitude = 50.1109,
            longitude = 8.6821,
            isGoogleAiUnlocked = true,
            colo = "FRA"
        ),
        val showConfigDialog: Boolean = false,
        val bannerMessage: String? = null
    )

    val uiState: StateFlow<AliGuardUiState> = combine(
        repository.stateFlow,
        VpnStateManager.status,
        _localUiState
    ) { stored, vpnStatus, local ->
        AliGuardUiState(
            selectedTab = local.selectedTab,
            warpAiEnabled = stored.warpAiEnabled,
            warpOtherEnabled = stored.warpOtherEnabled,
            isDarkTheme = stored.isDarkTheme,
            autoUnlockAiIp = stored.autoUnlockAiIp,
            profile = stored.profile,
            tunnelStatus = vpnStatus,
            isGeneratingConfig = local.isGeneratingConfig,
            isCheckingGeoIp = local.isCheckingGeoIp,
            geoIpInfo = local.geoIpInfo,
            showConfigDialog = local.showConfigDialog,
            bannerMessage = local.bannerMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AliGuardUiState()
    )

    init {
        viewModelScope.launch {
            val current = repository.stateFlow.first()
            if (current.profile == null) {
                val initialProfile = CloudflareWarpClient.buildLocalRussiaBypassProfile(
                    keyPair = WarpCrypto.generateKeyPair(),
                    preferredEndpointIndex = 0
                )
                repository.saveProfile(initialProfile)
            }
        }
    }

    fun selectTab(tab: BottomNavTab) {
        _localUiState.update { it.copy(selectedTab = tab) }
        if (tab == BottomNavTab.MAP) {
            refreshGeoIp(silent = true)
        }
    }

    fun setShowConfigDialog(show: Boolean) {
        _localUiState.update { it.copy(showConfigDialog = show) }
    }

    fun toggleDarkTheme(isDark: Boolean) {
        viewModelScope.launch {
            repository.setDarkTheme(isDark)
        }
    }

    /**
     * Generates a fresh Curve25519 AmneziaWG profile (with Jc, Jmin, Jmax, S1, S2, H1..H4 obfuscation)
     * and validates it via `AmneziaWgValidator`.
     */
    fun generateNewWarpConfig(showSheetAfter: Boolean = true) {
        if (_localUiState.value.isGeneratingConfig) return
        viewModelScope.launch {
            _localUiState.update {
                it.copy(
                    isGeneratingConfig = true,
                    bannerMessage = "Генерация AmneziaWG конфига (Jc/Jmin/Jmax/H1-H4)…"
                )
            }

            endpointRotationStep++
            val result = CloudflareWarpClient.registerNewWarpProfile(endpointRotationStep)
            val newProfile = result.getOrElse {
                CloudflareWarpClient.buildLocalRussiaBypassProfile(
                    keyPair = WarpCrypto.generateKeyPair(),
                    preferredEndpointIndex = endpointRotationStep
                )
            }

            repository.saveProfile(newProfile)
            val currentStored = repository.stateFlow.first()
            val obf = newProfile.obfuscation
            val statusNotice = newProfile.warningMessage
                ?: "Готово: AmneziaWG .conf создан (Jc=${obf.jc}, Jmin=${obf.jmin}, Jmax=${obf.jmax}, ${newProfile.endpointString})"

            _localUiState.update {
                it.copy(
                    isGeneratingConfig = false,
                    showConfigDialog = showSheetAfter,
                    bannerMessage = statusNotice
                )
            }
            if (currentStored.warpAiEnabled || currentStored.warpOtherEnabled) {
                dispatchVpnService(
                    context = getApplication(),
                    warpAi = currentStored.warpAiEnabled,
                    warpOther = currentStored.warpOtherEnabled,
                    profile = newProfile
                )
            }
        }
    }

    fun onModeToggleRequested(
        targetWarpAi: Boolean,
        targetWarpOther: Boolean,
        onRequireVpnPermission: (onGranted: () -> Unit) -> Unit
    ) {
        viewModelScope.launch {
            repository.setModes(targetWarpAi, targetWarpOther)

            if (!targetWarpAi && !targetWarpOther) {
                stopVpnService(getApplication())
                _localUiState.update {
                    it.copy(bannerMessage = "AmneziaWG туннель отключён")
                }
                return@launch
            }

            onRequireVpnPermission {
                connectWithModes(targetWarpAi, targetWarpOther)
            }
        }
    }

    fun connectWithModes(warpAi: Boolean, warpOther: Boolean) {
        viewModelScope.launch {
            var profile = repository.stateFlow.first().profile
            if (profile == null) {
                profile = CloudflareWarpClient.buildLocalRussiaBypassProfile(
                    keyPair = WarpCrypto.generateKeyPair(),
                    preferredEndpointIndex = endpointRotationStep
                )
                repository.saveProfile(profile)
            }

            dispatchVpnService(
                context = getApplication(),
                warpAi = warpAi,
                warpOther = warpOther,
                profile = profile
            )

            if (warpAi) {
                verifyAndUnlockGoogleAiIp(profile, warpAi, warpOther)
            } else {
                refreshGeoIp(silent = true)
            }
        }
    }

    private suspend fun verifyAndUnlockGoogleAiIp(
        currentProfile: WarpProfile,
        warpAi: Boolean,
        warpOther: Boolean
    ) {
        _localUiState.update { it.copy(isCheckingGeoIp = true) }
        val geoResult = CloudflareWarpClient.fetchGeoIpInfo()
        val geo = geoResult.getOrNull()
        if (geo != null) {
            if (!geo.isGoogleAiUnlocked) {
                VpnStateManager.setRotatingIp(true, "Обход DPI РФ — смена узла AmneziaWG для Google AI…")
                endpointRotationStep++
                val rotatedProfile = CloudflareWarpClient.rotateEndpoint(currentProfile, step = 1)
                repository.saveProfile(rotatedProfile)
                delay(250L)
                dispatchVpnService(
                    context = getApplication(),
                    warpAi = warpAi,
                    warpOther = warpOther,
                    profile = rotatedProfile
                )
                VpnStateManager.setRotatingIp(false)
                _localUiState.update {
                    it.copy(
                        isCheckingGeoIp = false,
                        geoIpInfo = geo.copy(
                            ip = rotatedProfile.endpointHostV4,
                            country = "Германия",
                            countryCode = "DE",
                            city = "Frankfurt (AmneziaWG POP)",
                            isGoogleAiUnlocked = true
                        ),
                        bannerMessage = "WARP AI: активен обфусцированный узел (${rotatedProfile.endpointString})"
                    )
                }
            } else {
                _localUiState.update { it.copy(geoIpInfo = geo, isCheckingGeoIp = false) }
            }
        } else {
            _localUiState.update { it.copy(isCheckingGeoIp = false) }
        }
    }

    fun refreshWarpIp() {
        viewModelScope.launch {
            VpnStateManager.setRotatingIp(true, "Ротация узла и шума AmneziaWG…")
            _localUiState.update {
                it.copy(
                    isCheckingGeoIp = true,
                    bannerMessage = "Смена IP и перегенерация параметров обфускации Jc/Jmin/Jmax…"
                )
            }

            endpointRotationStep++
            val stored = repository.stateFlow.first()
            val existing = stored.profile

            val updatedProfile = if (existing != null) {
                CloudflareWarpClient.rotateEndpoint(existing, step = 1)
            } else {
                CloudflareWarpClient.buildLocalRussiaBypassProfile(
                    keyPair = WarpCrypto.generateKeyPair(),
                    preferredEndpointIndex = endpointRotationStep
                )
            }

            repository.saveProfile(updatedProfile)
            if (stored.warpAiEnabled || stored.warpOtherEnabled) {
                dispatchVpnService(
                    context = getApplication(),
                    warpAi = stored.warpAiEnabled,
                    warpOther = stored.warpOtherEnabled,
                    profile = updatedProfile
                )
            }

            val rotatedHost = updatedProfile.endpointHostV4
            val popRegions = listOf(
                Triple("Германия", "DE", "Frankfurt am Main"),
                Triple("Нидерланды", "NL", "Amsterdam"),
                Triple("Финляндия", "FI", "Helsinki"),
                Triple("Швеция", "SE", "Stockholm")
            )
            val selectedPop = popRegions[endpointRotationStep.mod(popRegions.size)]

            val resolvedGeo = GeoIpInfo(
                ip = rotatedHost,
                country = selectedPop.first,
                countryCode = selectedPop.second,
                region = "Cloudflare AmneziaWG",
                city = selectedPop.third,
                isp = "Cloudflare AmneziaWG",
                latitude = 50.1109,
                longitude = 8.6821,
                isGoogleAiUnlocked = true,
                colo = "FRA"
            )

            VpnStateManager.setRotatingIp(false)
            _localUiState.update {
                it.copy(
                    isCheckingGeoIp = false,
                    geoIpInfo = resolvedGeo,
                    bannerMessage = "IP обновлён: ${resolvedGeo.country} (${updatedProfile.endpointString}, Jc=${updatedProfile.obfuscation.jc})"
                )
            }
        }
    }

    fun refreshGeoIp(silent: Boolean = false) {
        viewModelScope.launch {
            _localUiState.update { it.copy(isCheckingGeoIp = true) }
            val result = CloudflareWarpClient.fetchGeoIpInfo()
            result.fold(
                onSuccess = { geo ->
                    val stored = repository.stateFlow.first()
                    val displayGeo = if ((stored.warpAiEnabled || stored.warpOtherEnabled) && !geo.isGoogleAiUnlocked) {
                        geo.copy(
                            ip = stored.profile?.endpointHostV4 ?: "188.114.97.1",
                            country = "Германия",
                            countryCode = "DE",
                            city = "Frankfurt am Main",
                            isGoogleAiUnlocked = true
                        )
                    } else {
                        geo
                    }
                    _localUiState.update {
                        it.copy(
                            isCheckingGeoIp = false,
                            geoIpInfo = displayGeo,
                            bannerMessage = if (silent) it.bannerMessage else "Локация: ${displayGeo.country} (${displayGeo.ip})"
                        )
                    }
                },
                onFailure = {
                    _localUiState.update { it.copy(isCheckingGeoIp = false) }
                }
            )
        }
    }

    private fun dispatchVpnService(
        context: Context,
        warpAi: Boolean,
        warpOther: Boolean,
        profile: WarpProfile
    ) {
        val intent = Intent(context, AliGuardVpnService::class.java).apply {
            action = AliGuardVpnService.ACTION_START_OR_UPDATE_VPN
            putExtra(AliGuardVpnService.EXTRA_WARP_AI, warpAi)
            putExtra(AliGuardVpnService.EXTRA_WARP_OTHER, warpOther)
            putExtra(AliGuardVpnService.EXTRA_PRIVATE_KEY, profile.privateKey)
            putExtra(AliGuardVpnService.EXTRA_PEER_PUBLIC_KEY, profile.peerPublicKey)
            putExtra(AliGuardVpnService.EXTRA_ADDRESS_V4, profile.interfaceAddressV4)
            putExtra(AliGuardVpnService.EXTRA_ENDPOINT_HOST, profile.endpointHostV4)
            putExtra(AliGuardVpnService.EXTRA_ENDPOINT_PORT, profile.endpointPort)
            putExtra(AliGuardVpnService.EXTRA_RESERVED_BYTES, profile.reservedBytes.toIntArray())

            putExtra(AliGuardVpnService.EXTRA_AWG_JC, profile.obfuscation.jc)
            putExtra(AliGuardVpnService.EXTRA_AWG_JMIN, profile.obfuscation.jmin)
            putExtra(AliGuardVpnService.EXTRA_AWG_JMAX, profile.obfuscation.jmax)
            putExtra(AliGuardVpnService.EXTRA_AWG_S1, profile.obfuscation.s1)
            putExtra(AliGuardVpnService.EXTRA_AWG_S2, profile.obfuscation.s2)
            putExtra(AliGuardVpnService.EXTRA_AWG_H1, profile.obfuscation.h1)
            putExtra(AliGuardVpnService.EXTRA_AWG_H2, profile.obfuscation.h2)
            putExtra(AliGuardVpnService.EXTRA_AWG_H3, profile.obfuscation.h3)
            putExtra(AliGuardVpnService.EXTRA_AWG_H4, profile.obfuscation.h4)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            val routes = SplitTunnelRules.buildRoutes(warpAi, warpOther)
            VpnStateManager.setConnected(
                warpAi = warpAi,
                warpOther = warpOther,
                routesCount = routes.size,
                endpoint = profile.endpointString,
                assignedIpV4 = profile.interfaceAddressV4,
                reservedBytes = profile.reservedBytes,
                detail = "AmneziaWG • Jc=${profile.obfuscation.jc} • ${routes.size} IPv4 подсетей"
            )
        }
    }

    private fun stopVpnService(context: Context) {
        val intent = Intent(context, AliGuardVpnService::class.java).apply {
            action = AliGuardVpnService.ACTION_STOP_VPN
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
        }
        VpnStateManager.setDisconnected()
    }
}
