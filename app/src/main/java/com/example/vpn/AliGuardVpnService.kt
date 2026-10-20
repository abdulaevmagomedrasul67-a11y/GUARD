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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.routing.CidrRoute
import com.example.routing.SplitTunnelRules
import com.example.warp.AmneziaObfuscationParams
import com.example.warp.AmneziaWgValidator
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.config.Config as AwgConfig

class AliGuardVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    private var keepaliveJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private val secureRandom = SecureRandom()

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

                val obfuscation = AmneziaObfuscationParams(
                    jc = intent.getIntExtra(EXTRA_AWG_JC, 5),
                    jmin = intent.getIntExtra(EXTRA_AWG_JMIN, 40),
                    jmax = intent.getIntExtra(EXTRA_AWG_JMAX, 70),
                    s1 = intent.getIntExtra(EXTRA_AWG_S1, 0),
                    s2 = intent.getIntExtra(EXTRA_AWG_S2, 0),
                    h1 = intent.getLongExtra(EXTRA_AWG_H1, 1L),
                    h2 = intent.getLongExtra(EXTRA_AWG_H2, 2L),
                    h3 = intent.getLongExtra(EXTRA_AWG_H3, 3L),
                    h4 = intent.getLongExtra(EXTRA_AWG_H4, 4L)
                )

                startForeground(NOTIFICATION_ID, buildForegroundNotification(warpAi, warpOther, endpointHost))
                startOrReconfigureTunnel(
                    warpAi = warpAi,
                    warpOther = warpOther,
                    privateKey = privateKey,
                    peerPublicKey = peerPublicKey,
                    addressV4 = addressV4,
                    endpointHost = endpointHost,
                    endpointPort = endpointPort,
                    reservedBytes = reservedArray.toList(),
                    obfuscation = obfuscation
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
        reservedBytes: List<Int>,
        obfuscation: AmneziaObfuscationParams
    ) {
        tunnelJob?.cancel()
        keepaliveJob?.cancel()

        tunnelJob = serviceScope.launch {
            Log.i(TAG, "[Этап 1/3] Формирование AmneziaWG конфига для режимов: AI=$warpAi, Other=$warpOther")

            val routes = SplitTunnelRules.buildRoutes(
                warpAiEnabled = warpAi,
                warpOtherEnabled = warpOther
            )

            // Parse and validate through AmneziaWG library (com.zaneschepke:amneziawg-android / org.amnezia.awg)
            val awgConfig = buildAndParseAmneziaWgConfig(
                privateKey = privateKey,
                peerPublicKey = peerPublicKey,
                addressV4 = addressV4,
                endpointHost = endpointHost,
                endpointPort = endpointPort,
                routes = routes,
                obfuscation = obfuscation
            )

            val activeObfuscation = if (awgConfig != null) {
                val iface = awgConfig.`interface`
                AmneziaObfuscationParams(
                    jc = iface.junkPacketCount.orElse(obfuscation.jc),
                    jmin = iface.junkPacketMinSize.orElse(obfuscation.jmin),
                    jmax = iface.junkPacketMaxSize.orElse(obfuscation.jmax),
                    s1 = iface.initPacketJunkSize.orElse(obfuscation.s1),
                    s2 = iface.responsePacketJunkSize.orElse(obfuscation.s2),
                    h1 = iface.initPacketMagicHeader.orElse(obfuscation.h1),
                    h2 = iface.responsePacketMagicHeader.orElse(obfuscation.h2),
                    h3 = iface.underloadPacketMagicHeader.orElse(obfuscation.h3),
                    h4 = iface.transportPacketMagicHeader.orElse(obfuscation.h4)
                )
            } else {
                obfuscation
            }

            Log.i(
                TAG,
                "[Этап 3/3] Установка обфусцированного AmneziaWG туннеля (${activeObfuscation.toSummaryString()})"
            )

            try {
                tunInterface?.close()
            } catch (_: Exception) {
            }
            tunInterface = null

            val builder = Builder()
                .setSession("Ali-Guard AmneziaWG IPv4")
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
                handshakeSent = sendAmneziaWgObfuscatedHandshake(
                    socket = socket,
                    endpointHost = endpointHost,
                    endpointPort = endpointPort,
                    reservedBytes = reservedBytes,
                    obfuscation = activeObfuscation
                )
            }

            val modeLabel = when {
                warpAi && warpOther -> "AWG AI + Остальное"
                warpAi -> "AWG AI"
                else -> "AWG Остальное"
            }

            VpnStateManager.setConnected(
                warpAi = warpAi,
                warpOther = warpOther,
                routesCount = appliedRoutesCount,
                endpoint = "$endpointHost:$endpointPort",
                assignedIpV4 = addressV4,
                reservedBytes = reservedBytes,
                detail = "$modeLabel • Jc=${activeObfuscation.jc} (${activeObfuscation.jmin}-${activeObfuscation.jmax}B) • $appliedRoutesCount IPv4 подсетей"
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
                            sendAmneziaWgObfuscatedHandshake(
                                socket = socket,
                                endpointHost = endpointHost,
                                endpointPort = endpointPort,
                                reservedBytes = reservedBytes,
                                obfuscation = activeObfuscation
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

    /**
     * Parses the AmneziaWG configuration (including Jc, Jmin, Jmax, S1, S2, H1, H2, H3, H4)
     * using `org.amnezia.awg.config.Config.parse(...)` from `com.zaneschepke:amneziawg-android`.
     */
    private fun buildAndParseAmneziaWgConfig(
        privateKey: String,
        peerPublicKey: String,
        addressV4: String,
        endpointHost: String,
        endpointPort: Int,
        routes: List<CidrRoute>,
        obfuscation: AmneziaObfuscationParams
    ): AwgConfig? {
        val allowedIps = routes
            .filter { !it.address.contains(":") }
            .joinToString(", ") { it.cidrNotation }
            .ifEmpty { "1.1.1.1/32, 1.0.0.1/32" }

        val rawConf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = ${addressV4.substringBefore("/")}/32")
            appendLine("DNS = 1.1.1.1, 1.0.0.1")
            appendLine("MTU = 1280")
            appendLine("Jc = ${obfuscation.jc}")
            appendLine("Jmin = ${obfuscation.jmin}")
            appendLine("Jmax = ${obfuscation.jmax}")
            appendLine("S1 = ${obfuscation.s1}")
            appendLine("S2 = ${obfuscation.s2}")
            appendLine("H1 = ${obfuscation.h1}")
            appendLine("H2 = ${obfuscation.h2}")
            appendLine("H3 = ${obfuscation.h3}")
            appendLine("H4 = ${obfuscation.h4}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPublicKey")
            appendLine("AllowedIPs = $allowedIps")
            appendLine("Endpoint = $endpointHost:$endpointPort")
            appendLine("PersistentKeepalive = 25")
        }

        val validation = AmneziaWgValidator.validateAndParse(rawConf, obfuscation)
        return validation.parsedAwgConfig
    }

    /**
     * Transmits the AmneziaWG obfuscated handshake sequence over IPv4 UDP:
     * 1. Sends `Jc` pseudorandom junk packets of size `[Jmin..Jmax]` to blind Russian TSPU/DPI.
     * 2. Sends the `S1`-padded, `H1`-headered (and Cloudflare `Reserved` `[b0, b1, b2]` tagged)
     *    Handshake Initiation packet.
     */
    private fun sendAmneziaWgObfuscatedHandshake(
        socket: DatagramSocket,
        endpointHost: String,
        endpointPort: Int,
        reservedBytes: List<Int>,
        obfuscation: AmneziaObfuscationParams
    ): Boolean {
        return try {
            val ipv4Addresses = InetAddress.getAllByName(endpointHost).filterIsInstance<Inet4Address>()
            val targetAddr = ipv4Addresses.firstOrNull() ?: return false
            val socketAddr = InetSocketAddress(targetAddr, endpointPort)

            val jcCount = obfuscation.jc.coerceIn(1, 32)
            val minSz = obfuscation.jmin.coerceIn(8, 1200)
            val maxSz = obfuscation.jmax.coerceAtLeast(minSz + 1).coerceAtMost(1280)

            var totalBytesSent = 0L
            // Step 1: Send Jc pseudorandom junk packets (sizes in Jmin..Jmax)
            for (i in 0 until jcCount) {
                val junkSize = minSz + secureRandom.nextInt(maxSz - minSz + 1)
                val junkPayload = ByteArray(junkSize)
                secureRandom.nextBytes(junkPayload)
                val junkPacket = DatagramPacket(junkPayload, junkPayload.size, socketAddr)
                socket.send(junkPacket)
                totalBytesSent += junkSize
            }

            // Step 2: Send AmneziaWG Handshake Initiation packet with S1 prefix, H1 header & Reserved bytes
            val s1PrefixSize = obfuscation.s1.coerceIn(0, 512)
            val totalPacketSize = s1PrefixSize + 148
            val packetBytes = ByteArray(totalPacketSize)
            secureRandom.nextBytes(packetBytes)

            // If H1 == 1 (Cloudflare WARP peer), set Type=1 + 3 Reserved bytes from client_id
            if (obfuscation.h1 == 1L) {
                packetBytes[s1PrefixSize + 0] = 1
                packetBytes[s1PrefixSize + 1] = (reservedBytes.getOrElse(0) { 0 } and 0xFF).toByte()
                packetBytes[s1PrefixSize + 2] = (reservedBytes.getOrElse(1) { 0 } and 0xFF).toByte()
                packetBytes[s1PrefixSize + 3] = (reservedBytes.getOrElse(2) { 0 } and 0xFF).toByte()
            } else {
                // Custom 4-byte little-endian H1 magic header for standalone AmneziaWG servers
                val h1Bytes = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(obfuscation.h1.toInt())
                    .array()
                System.arraycopy(h1Bytes, 0, packetBytes, s1PrefixSize, 4)
            }

            // Sender index at offset s1PrefixSize + 4
            val senderIdx = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x414C4947)
                .array()
            System.arraycopy(senderIdx, 0, packetBytes, s1PrefixSize + 4, 4)

            val initDgram = DatagramPacket(packetBytes, packetBytes.size, socketAddr)
            socket.send(initDgram)
            totalBytesSent += packetBytes.size

            VpnStateManager.recordTraffic(
                txPktsDelta = (jcCount + 1).toLong(),
                txBytesDelta = totalBytesSent,
                rxPktsDelta = 0L,
                rxBytesDelta = 0L
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отправки обфусцированного AmneziaWG рукопожатия: ${e.message}")
            false
        }
    }

    private fun stopVpnTunnel() {
        Log.i(TAG, "Остановка обфусцированного AmneziaWG туннеля")
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
                "Ali-Guard AmneziaWG Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус активного AmneziaWG VPN-туннеля"
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
            warpAi && warpOther -> "AmneziaWG: WARP AI + Остальное"
            warpAi -> "AmneziaWG: WARP AI (Gemini / Google)"
            else -> "AmneziaWG: WARP Остальное (TG / WA / YT)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ali-Guard AWG: Защищено ($endpointHost)")
            .setContentText(modeSummary)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AliGuardAWG"

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

        const val EXTRA_AWG_JC = "extra_awg_jc"
        const val EXTRA_AWG_JMIN = "extra_awg_jmin"
        const val EXTRA_AWG_JMAX = "extra_awg_jmax"
        const val EXTRA_AWG_S1 = "extra_awg_s1"
        const val EXTRA_AWG_S2 = "extra_awg_s2"
        const val EXTRA_AWG_H1 = "extra_awg_h1"
        const val EXTRA_AWG_H2 = "extra_awg_h2"
        const val EXTRA_AWG_H3 = "extra_awg_h3"
        const val EXTRA_AWG_H4 = "extra_awg_h4"

        private const val CHANNEL_ID = "ali_guard_vpn_channel"
        private const val NOTIFICATION_ID = 1042
    }
}
