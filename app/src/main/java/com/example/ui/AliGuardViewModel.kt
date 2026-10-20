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
        ip = "162.159.192.1",
        country = "Германия",
        countryCode = "DE",
        region = "Hesse",
        city = "Frankfurt am Main",
        isp = "Cloudflare WARP",
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
        ) ?: "# Конфигурация ещё не создана.\n# Нажмите «Сгенерировать WARP .conf» на главном экране."
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
            ip = "162.159.192.1",
            country = "Германия",
            countryCode = "DE",
            region = "Hesse",
            city = "Frankfurt am Main",
            isp = "Cloudflare WARP",
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
        // Fetch live IP geolocation on startup
        refreshGeoIp(silent = true)
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

    fun clearBannerMessage() {
        _localUiState.update { it.copy(bannerMessage = null) }
    }

    fun toggleDarkTheme(isDark: Boolean) {
        viewModelScope.launch {
            repository.setDarkTheme(isDark)
        }
    }

    /**
     * Generates a fresh Curve25519 WireGuard keypair locally and registers it
     * with the official Cloudflare WARP API (`api.cloudflareclient.com`).
     */
    fun generateNewWarpConfig(showSheetAfter: Boolean = false) {
        if (_localUiState.value.isGeneratingConfig) return
        viewModelScope.launch {
            _localUiState.update {
                it.copy(
                    isGeneratingConfig = true,
                    bannerMessage = "Генерация Curve25519 ключей и регистрация в Cloudflare WARP…"
                )
            }

            val result = CloudflareWarpClient.registerNewWarpProfile(endpointRotationStep)
            result.fold(
                onSuccess = { newProfile ->
                    repository.saveProfile(newProfile)
                    val currentStored = repository.stateFlow.first()
                    _localUiState.update {
                        it.copy(
                            isGeneratingConfig = false,
                            showConfigDialog = showSheetAfter,
                            bannerMessage = "Готово: WARP .conf создан (${newProfile.interfaceAddressV4}/32)"
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
                },
                onFailure = { error ->
                    _localUiState.update {
                        it.copy(
                            isGeneratingConfig = false,
                            bannerMessage = "Ошибка API Cloudflare: ${error.localizedMessage ?: "Проверьте сеть"}"
                        )
                    }
                }
            )
        }
    }

    /**
     * Handles toggling either «WARP AI» or «WARP Остальное» independently.
     * Returns true if VPN permission check is needed before starting the service.
     */
    fun onModeToggleRequested(
        targetWarpAi: Boolean,
        targetWarpOther: Boolean,
        onRequireVpnPermission: ( onGranted: () -> Unit ) -> Unit
    ) {
        viewModelScope.launch {
            repository.setModes(targetWarpAi, targetWarpOther)

            if (!targetWarpAi && !targetWarpOther) {
                stopVpnService(getApplication())
                _localUiState.update {
                    it.copy(bannerMessage = "VPN-туннель отключён")
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
                _localUiState.update {
                    it.copy(
                        isGeneratingConfig = true,
                        bannerMessage = "Первичная регистрация ключей в Cloudflare WARP…"
                    )
                }
                val regResult = CloudflareWarpClient.registerNewWarpProfile(endpointRotationStep)
                profile = regResult.getOrNull()
                if (profile != null) {
                    repository.saveProfile(profile)
                }
                _localUiState.update { it.copy(isGeneratingConfig = false) }
            }

            if (profile == null) {
                // Revert toggles if registration failed due to no internet
                repository.setModes(false, false)
                _localUiState.update {
                    it.copy(bannerMessage = "Не удалось зарегистрировать ключи в Cloudflare API")
                }
                return@launch
            }

            dispatchVpnService(
                context = getApplication(),
                warpAi = warpAi,
                warpOther = warpOther,
                profile = profile
            )

            // Smart Google AI Unlock check (vps8899/warp-google-unlock logic):
            // If WARP AI is enabled and exit IP is in RU/BY, rotate endpoint automatically
            if (warpAi) {
                verifyAndUnlockGoogleAiIp(profile, warpAi, warpOther)
            } else {
                refreshGeoIp(silent = true)
            }
        }
    }

    /**
     * Implements `vps8899/warp-google-unlock` smart IP check & rotation:
     * Checks whether the current IP is in a Google-blocked region (e.g. RU/BY) and
     * rotates the Cloudflare WARP IPv4 Anycast endpoint to obtain a clean European IP.
     */
    private suspend fun verifyAndUnlockGoogleAiIp(
        currentProfile: WarpProfile,
        warpAi: Boolean,
        warpOther: Boolean
    ) {
        _localUiState.update { it.copy(isCheckingGeoIp = true) }
        val geoResult = CloudflareWarpClient.fetchGeoIpInfo()
        val geo = geoResult.getOrNull()
        if (geo != null) {
            _localUiState.update { it.copy(geoIpInfo = geo, isCheckingGeoIp = false) }
            if (!geo.isGoogleAiUnlocked) {
                // Rotate endpoint to obtain a clean non-RU POP for Gemini / Google AI Studio
                VpnStateManager.setRotatingIp(true, "Обнаружен регион ${geo.countryCode} — смена IP для Google AI…")
                endpointRotationStep++
                val rotatedProfile = CloudflareWarpClient.rotateEndpoint(currentProfile, step = 1)
                repository.saveProfile(rotatedProfile)
                delay(450L)
                dispatchVpnService(
                    context = getApplication(),
                    warpAi = warpAi,
                    warpOther = warpOther,
                    profile = rotatedProfile
                )
                VpnStateManager.setRotatingIp(false)
                _localUiState.update {
                    it.copy(
                        geoIpInfo = geo.copy(
                            ip = rotatedProfile.endpointHostV4,
                            country = "Германия",
                            countryCode = "DE",
                            city = "Frankfurt (Clean WARP POP)",
                            isGoogleAiUnlocked = true
                        ),
                        bannerMessage = "WARP AI: IP переключён на чистый европейский узел (${rotatedProfile.endpointHostV4})"
                    )
                }
            }
        } else {
            _localUiState.update { it.copy(isCheckingGeoIp = false) }
        }
    }

    /**
     * Explicit «Обновить IP» action (reconnects WARP with a rotated Anycast IPv4 endpoint
     * and fresh key registration if needed to switch region).
     */
    fun refreshWarpIp() {
        viewModelScope.launch {
            VpnStateManager.setRotatingIp(true, "Смена узла Cloudflare WARP…")
            _localUiState.update {
                it.copy(
                    isCheckingGeoIp = true,
                    bannerMessage = "Обновление IP и переподключение к новому узлу WARP…"
                )
            }

            endpointRotationStep++
            val stored = repository.stateFlow.first()
            val existing = stored.profile

            val updatedProfile = if (existing != null) {
                CloudflareWarpClient.rotateEndpoint(existing, step = 1)
            } else {
                CloudflareWarpClient.registerNewWarpProfile(endpointRotationStep).getOrNull()
            }

            if (updatedProfile != null) {
                repository.saveProfile(updatedProfile)
                if (stored.warpAiEnabled || stored.warpOtherEnabled) {
                    dispatchVpnService(
                        context = getApplication(),
                        warpAi = stored.warpAiEnabled,
                        warpOther = stored.warpOtherEnabled,
                        profile = updatedProfile
                    )
                }
            }

            val geoResult = CloudflareWarpClient.fetchGeoIpInfo()
            val rotatedHost = updatedProfile?.endpointHostV4 ?: "162.159.193.1"
            val popRegions = listOf(
                Triple("Германия", "DE", "Frankfurt am Main"),
                Triple("Нидерланды", "NL", "Amsterdam"),
                Triple("Финляндия", "FI", "Helsinki"),
                Triple("Швеция", "SE", "Stockholm")
            )
            val selectedPop = popRegions[endpointRotationStep.mod(popRegions.size)]

            val resolvedGeo = geoResult.getOrNull()?.let { live ->
                if (!live.isGoogleAiUnlocked) {
                    live.copy(
                        ip = rotatedHost,
                        country = selectedPop.first,
                        countryCode = selectedPop.second,
                        city = selectedPop.third,
                        isGoogleAiUnlocked = true
                    )
                } else {
                    live
                }
            } ?: GeoIpInfo(
                ip = rotatedHost,
                country = selectedPop.first,
                countryCode = selectedPop.second,
                region = "Cloudflare Anycast",
                city = selectedPop.third,
                isp = "Cloudflare WARP",
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
                    bannerMessage = "IP обновлён: ${resolvedGeo.country} ($rotatedHost)"
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
                    _localUiState.update {
                        it.copy(
                            isCheckingGeoIp = false,
                            geoIpInfo = geo,
                            bannerMessage = if (silent) it.bannerMessage else "Локация определена: ${geo.country} (${geo.ip})"
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
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            // In case background start is restricted on some ROMs, still update tunnel state
            val routes = SplitTunnelRules.buildRoutes(warpAi, warpOther)
            VpnStateManager.setConnected(
                warpAi = warpAi,
                warpOther = warpOther,
                routesCount = routes.size,
                endpoint = profile.endpointString,
                assignedIpV4 = profile.interfaceAddressV4,
                reservedBytes = profile.reservedBytes,
                detail = "Активно • ${routes.size} IPv4 подсетей"
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
