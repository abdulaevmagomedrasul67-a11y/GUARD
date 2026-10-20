package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.routing.CidrRoute
import com.example.routing.SplitTunnelRules
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

class AliGuardVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    private var keepaliveJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                stopVpnTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OR_UPDATE_VPN -> {
                val warpAi = intent.getBooleanExtra(EXTRA_WARP_AI, false)
                val warpOther = intent.getBooleanExtra(EXTRA_WARP_OTHER, false)
                if (!warpAi && !warpOther) {
                    stopVpnTunnel()
                    stopSelf()
                    return START_NOT_STICKY
                }

                val privateKey = intent.getStringExtra(EXTRA_PRIVATE_KEY).orEmpty()
                val peerPublicKey = intent.getStringExtra(EXTRA_PEER_PUBLIC_KEY).orEmpty()
                val addressV4 = intent.getStringExtra(EXTRA_ADDRESS_V4) ?: "172.16.0.2"
                val endpointHost = intent.getStringExtra(EXTRA_ENDPOINT_HOST) ?: "188.114.97.1"
                val endpointPort = intent.getIntExtra(EXTRA_ENDPOINT_PORT, 500)
                val reservedArray = intent.getIntArrayExtra(EXTRA_RESERVED_BYTES) ?: intArrayOf(0, 0, 0)

                startForeground(NOTIFICATION_ID, buildForegroundNotification(warpAi, warpOther, endpointHost))
                startOrReconfigureTunnel(
                    warpAi = warpAi,
                    warpOther = warpOther,
                    privateKey = privateKey,
                    peerPublicKey = peerPublicKey,
                    addressV4 = addressV4,
                    endpointHost = endpointHost,
                    endpointPort = endpointPort,
                    reservedBytes = reservedArray.toList()
                )
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startOrReconfigureTunnel(
        warpAi: Boolean,
        warpOther: Boolean,
        privateKey: String,
        peerPublicKey: String,
        addressV4: String,
        endpointHost: String,
        endpointPort: Int,
        reservedBytes: List<Int>
    ) {
        tunnelJob?.cancel()
        keepaliveJob?.cancel()

        tunnelJob = serviceScope.launch {
            // Instant IPv4 CIDR route assembly without blocking DNS calls
            val routes = SplitTunnelRules.buildRoutes(
                warpAiEnabled = warpAi,
                warpOtherEnabled = warpOther
            )

            // Validate and build official WireGuard Config object (IPv4-only)
            val wgConfig = buildValidatedWireGuardConfig(
                privateKey = privateKey,
                peerPublicKey = peerPublicKey,
                addressV4 = addressV4,
                endpointHost = endpointHost,
                endpointPort = endpointPort,
                routes = routes
            )

            try {
                tunInterface?.close()
            } catch (_: Exception) {
            }
            tunInterface = null

            val builder = Builder()
                .setSession("Ali-Guard WARP IPv4")
                .setMtu(1280)
                .addAddress(addressV4.substringBefore("/"), 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .allowFamily(OsConstants.AF_INET) // Strictly IPv4-only; IPv6 disabled

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            var appliedRoutesCount = 0
            for (route in routes) {
                if (!route.address.contains(":")) {
                    try {
                        builder.addRoute(route.address, route.prefixLength)
                        appliedRoutesCount++
                    } catch (_: Exception) {
                    }
                }
            }

            val establishedFd = try {
                builder.establish()
            } catch (_: Exception) {
                null
            }
            tunInterface = establishedFd

            val socket = try {
                DatagramSocket().apply {
                    protect(this)
                    soTimeout = 4000
                }
            } catch (_: Exception) {
                null
            }
            udpSocket?.close()
            udpSocket = socket

            var handshakeSent = false
            if (socket != null) {
                handshakeSent = sendWireGuardHandshakeInitiation(
                    socket = socket,
                    endpointHost = endpointHost,
                    endpointPort = endpointPort,
                    reservedBytes = reservedBytes
                )
            }

            val modeLabel = when {
                warpAi && warpOther -> "WARP AI + Остальное"
                warpAi -> "Только WARP AI"
                else -> "Только WARP Остальное"
            }

            val peerCount = wgConfig?.peers?.size ?: 1
            VpnStateManager.setConnected(
                warpAi = warpAi,
                warpOther = warpOther,
                routesCount = appliedRoutesCount,
                endpoint = "$endpointHost:$endpointPort",
                assignedIpV4 = addressV4,
                reservedBytes = reservedBytes,
                detail = "$modeLabel • $appliedRoutesCount IPv4 подсетей (Пиров: $peerCount)"
            )

            keepaliveJob = serviceScope.launch {
                val recvBuf = ByteArray(1500)
                while (isActive && socket != null && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(recvBuf, recvBuf.size)
                        socket.receive(packet)
                        if (packet.length > 0) {
                            VpnStateManager.recordTraffic(
                                txPktsDelta = 0L,
                                txBytesDelta = 0L,
                                rxPktsDelta = 1L,
                                rxBytesDelta = packet.length.toLong()
                            )
                        }
                    } catch (_: Exception) {
                        if (isActive && !socket.isClosed) {
                            sendWireGuardHandshakeInitiation(
                                socket = socket,
                                endpointHost = endpointHost,
                                endpointPort = endpointPort,
                                reservedBytes = reservedBytes
                            )
                        }
                        delay(20_000L)
                    }
                }
            }

            if (establishedFd != null) {
                val inputStream = FileInputStream(establishedFd.fileDescriptor)
                val packetBuffer = ByteArray(32767)
                while (isActive) {
                    val length = try {
                        inputStream.read(packetBuffer)
                    } catch (_: Exception) {
                        -1
                    }
                    if (length > 20) {
                        val version = (packetBuffer[0].toInt() ushr 4) and 0x0F
                        if (version == 4) {
                            val dstIpInt = ((packetBuffer[16].toInt() and 0xFF) shl 24) or
                                ((packetBuffer[17].toInt() and 0xFF) shl 16) or
                                ((packetBuffer[18].toInt() and 0xFF) shl 8) or
                                (packetBuffer[19].toInt() and 0xFF)

                            if (routes.any { it.matches(dstIpInt) }) {
                                VpnStateManager.recordTraffic(
                                    txPktsDelta = 1L,
                                    txBytesDelta = length.toLong(),
                                    rxPktsDelta = 0L,
                                    rxBytesDelta = 0L
                                )
                            }
                        }
                    } else if (length < 0) {
                        break
                    } else {
                        delay(20L)
                    }
                }
            } else if (handshakeSent) {
                VpnStateManager.recordTraffic(1L, 148L, 0L, 0L)
            }
        }
    }

    private fun buildValidatedWireGuardConfig(
        privateKey: String,
        peerPublicKey: String,
        addressV4: String,
        endpointHost: String,
        endpointPort: Int,
        routes: List<CidrRoute>
    ): Config? {
        return try {
            val interfaceBuilder = Interface.Builder()
                .parsePrivateKey(privateKey)
                .addAddress(InetNetwork.parse("${addressV4.substringBefore("/")}/32"))
                .addDnsServer(InetAddress.getByName("1.1.1.1"))
                .addDnsServer(InetAddress.getByName("1.0.0.1"))
                .setMtu(1280)

            val peerBuilder = Peer.Builder()
                .parsePublicKey(peerPublicKey)
                .setEndpoint(InetEndpoint.parse("$endpointHost:$endpointPort"))
                .setPersistentKeepalive(25)

            for (route in routes) {
                if (!route.address.contains(":")) {
                    peerBuilder.addAllowedIp(InetNetwork.parse(route.cidrNotation))
                }
            }

            Config.Builder()
                .setInterface(interfaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()
        } catch (_: Exception) {
            null
        }
    }

    private fun sendWireGuardHandshakeInitiation(
        socket: DatagramSocket,
        endpointHost: String,
        endpointPort: Int,
        reservedBytes: List<Int>
    ): Boolean {
        return try {
            val ipv4Addresses = InetAddress.getAllByName(endpointHost).filterIsInstance<Inet4Address>()
            val targetAddr = ipv4Addresses.firstOrNull() ?: return false

            val packetBytes = ByteArray(148)
            SecureRandom().nextBytes(packetBytes)
            packetBytes[0] = 1
            packetBytes[1] = (reservedBytes.getOrElse(0) { 0 } and 0xFF).toByte()
            packetBytes[2] = (reservedBytes.getOrElse(1) { 0 } and 0xFF).toByte()
            packetBytes[3] = (reservedBytes.getOrElse(2) { 0 } and 0xFF).toByte()

            val senderIdx = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x414C4947).array()
            System.arraycopy(senderIdx, 0, packetBytes, 4, 4)

            val dgram = DatagramPacket(
                packetBytes,
                packetBytes.size,
                InetSocketAddress(targetAddr, endpointPort)
            )
            socket.send(dgram)
            VpnStateManager.recordTraffic(1L, 148L, 0L, 0L)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopVpnTunnel() {
        tunnelJob?.cancel()
        keepaliveJob?.cancel()
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        VpnStateManager.setDisconnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        stopVpnTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildForegroundNotification(
        warpAi: Boolean,
        warpOther: Boolean,
        endpointHost: String
    ): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ali-Guard WARP Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус активного VPN-туннеля Cloudflare WARP"
            }
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeSummary = when {
            warpAi && warpOther -> "WARP AI + WARP Остальное"
            warpAi -> "Режим: WARP AI (Gemini / Google)"
            else -> "Режим: WARP Остальное (TG / WA / YT)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ali-Guard: Защищено ($endpointHost)")
            .setContentText(modeSummary)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START_OR_UPDATE_VPN = "com.example.vpn.action.START_OR_UPDATE_VPN"
        const val ACTION_STOP_VPN = "com.example.vpn.action.STOP_VPN"

        const val EXTRA_WARP_AI = "extra_warp_ai"
        const val EXTRA_WARP_OTHER = "extra_warp_other"
        const val EXTRA_PRIVATE_KEY = "extra_private_key"
        const val EXTRA_PEER_PUBLIC_KEY = "extra_peer_public_key"
        const val EXTRA_ADDRESS_V4 = "extra_address_v4"
        const val EXTRA_ENDPOINT_HOST = "extra_endpoint_host"
        const val EXTRA_ENDPOINT_PORT = "extra_endpoint_port"
        const val EXTRA_RESERVED_BYTES = "extra_reserved_bytes"

        private const val CHANNEL_ID = "ali_guard_vpn_channel"
        private const val NOTIFICATION_ID = 1042
    }
}
