package com.example.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VpnTunnelStatus(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isRotatingIp: Boolean = false,
    val warpAiActive: Boolean = false,
    val warpOtherActive: Boolean = false,
    val activeRoutesCount: Int = 0,
    val activeEndpoint: String = "162.159.192.1:2408",
    val assignedIpV4: String = "172.16.0.2",
    val reservedBytes: List<Int> = listOf(0, 0, 0),
    val txPackets: Long = 0L,
    val rxPackets: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val statusDetail: String = "Туннель не активен"
)

object VpnStateManager {
    private val _status = MutableStateFlow(VpnTunnelStatus())
    val status: StateFlow<VpnTunnelStatus> = _status.asStateFlow()

    fun update(transform: (VpnTunnelStatus) -> VpnTunnelStatus) {
        _status.update(transform)
    }

    fun setConnecting(warpAi: Boolean, warpOther: Boolean, detail: String = "Инициализация IPv4-туннеля…") {
        _status.update {
            it.copy(
                isConnecting = true,
                warpAiActive = warpAi,
                warpOtherActive = warpOther,
                statusDetail = detail
            )
        }
    }

    fun setRotatingIp(rotating: Boolean, detail: String? = null) {
        _status.update {
            it.copy(
                isRotatingIp = rotating,
                statusDetail = detail ?: it.statusDetail
            )
        }
    }

    fun setConnected(
        warpAi: Boolean,
        warpOther: Boolean,
        routesCount: Int,
        endpoint: String,
        assignedIpV4: String,
        reservedBytes: List<Int>,
        detail: String
    ) {
        _status.update {
            it.copy(
                isConnected = true,
                isConnecting = false,
                isRotatingIp = false,
                warpAiActive = warpAi,
                warpOtherActive = warpOther,
                activeRoutesCount = routesCount,
                activeEndpoint = endpoint,
                assignedIpV4 = assignedIpV4,
                reservedBytes = reservedBytes,
                statusDetail = detail
            )
        }
    }

    fun recordTraffic(txPktsDelta: Long, txBytesDelta: Long, rxPktsDelta: Long, rxBytesDelta: Long) {
        _status.update {
            it.copy(
                txPackets = it.txPackets + txPktsDelta,
                txBytes = it.txBytes + txBytesDelta,
                rxPackets = it.rxPackets + rxPktsDelta,
                rxBytes = it.rxBytes + rxBytesDelta
            )
        }
    }

    fun setDisconnected(detail: String = "Туннель отключён") {
        _status.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                isRotatingIp = false,
                warpAiActive = false,
                warpOtherActive = false,
                activeRoutesCount = 0,
                statusDetail = detail
            )
        }
    }
}
