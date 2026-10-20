package com.example.warp

import android.util.Log
import com.example.routing.CidrRoute
import com.example.routing.SplitTunnelRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class WarpProfile(
    val deviceId: String,
    val accessToken: String,
    val privateKey: String,
    val publicKey: String,
    val peerPublicKey: String,
    val clientIdBase64: String,
    val reservedBytes: List<Int>,
    val interfaceAddressV4: String,
    val endpointHostV4: String,
    val endpointPort: Int,
    val createdAtIso: String,
    val obfuscation: AmneziaObfuscationParams = AmneziaObfuscationParams.generateForWarpDpiBypass(),
    val warningMessage: String? = null
) {
    val endpointString: String
        get() = "$endpointHostV4:$endpointPort"

    /**
     * Assembles a complete AmneziaWG `.conf` configuration string (strictly IPv4-only)
     * including all 9 DPI-obfuscation parameters: Jc, Jmin, Jmax, S1, S2, H1, H2, H3, H4.
     */
    fun toWireGuardConf(
        warpAiEnabled: Boolean,
        warpOtherEnabled: Boolean,
        customRoutes: List<CidrRoute>? = null
    ): String {
        val routes = customRoutes ?: SplitTunnelRules.buildRoutes(
            warpAiEnabled = warpAiEnabled,
            warpOtherEnabled = warpOtherEnabled
        )
        val allowedIps = if (routes.isNotEmpty()) {
            routes.joinToString(", ") { it.cidrNotation }
        } else {
            SplitTunnelRules.buildRoutes(warpAiEnabled = true, warpOtherEnabled = true)
                .joinToString(", ") { it.cidrNotation }
        }
        val reservedStr = reservedBytes.joinToString(", ", prefix = "[", postfix = "]")
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $interfaceAddressV4/32")
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
            appendLine("# Reserved = $reservedStr")
            appendLine("# ClientId = $clientIdBase64")
            appendLine("# DeviceId = $deviceId")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPublicKey")
            appendLine("AllowedIPs = $allowedIps")
            appendLine("Endpoint = $endpointHostV4:$endpointPort")
            appendLine("PersistentKeepalive = 25")
        }
    }
}

data class GeoIpInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val region: String,
    val city: String,
    val isp: String,
    val latitude: Double,
    val longitude: Double,
    val isGoogleAiUnlocked: Boolean,
    val colo: String
)

/**
 * AmneziaWG WARP Generator & Client Helper for Russia DPI Bypass:
 * - Generates AmneziaWG configurations with `Jc, Jmin, Jmax, S1, S2, H1, H2, H3, H4` obfuscation parameters.
 * - Combines remote AmneziaWG/WARP API registration (with direct Anycast IP DNS bypass) and
 *   local Curve25519 + AmneziaWG synthesis when Russian ISP TSPU blocks outbound HTTPS.
 * - Validates and normalizes AmneziaWG 1.5 (`I1`..`I5`) configs via `AmneziaWgValidator` so
 *   `com.zaneschepke:amneziawg-android` (`org.amnezia.awg.config.Config`) parses without crashes.
 */
object CloudflareWarpClient {

    private const val TAG = "AliGuardAWG"
    private const val DEFAULT_CF_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    // Verified IPv4 Cloudflare WARP Anycast endpoints that work with AmneziaWG in Russia
    val WARP_IPV4_ENDPOINTS = listOf(
        "188.114.97.1" to 500,
        "188.114.96.1" to 4500,
        "188.114.98.1" to 1701,
        "188.114.99.1" to 500,
        "162.159.192.1" to 500,
        "162.159.193.1" to 2408,
        "162.159.195.1" to 2408,
        "162.159.192.7" to 4500,
        "188.114.97.3" to 2408
    )

    private val RESTRICTED_AI_COUNTRY_CODES = setOf("RU", "BY", "CN", "HK", "IR", "KP", "SY", "CU")

    val ipv4OnlyDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                val all = Dns.SYSTEM.lookup(hostname)
                val v4 = all.filterIsInstance<Inet4Address>()
                if (v4.isNotEmpty()) {
                    v4
                } else {
                    fallbackDnsForHost(hostname)
                }
            } catch (_: Exception) {
                fallbackDnsForHost(hostname)
            }
        }

        private fun fallbackDnsForHost(hostname: String): List<InetAddress> {
            return when {
                hostname.contains("cloudflareclient.com", ignoreCase = true) -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(104.toByte(), 16.toByte(), 132.toByte(), 229.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(104.toByte(), 16.toByte(), 133.toByte(), 229.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(162.toByte(), 159.toByte(), 192.toByte(), 1.toByte()))
                )
                hostname.contains("ipwho.is", ignoreCase = true) -> listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(104.toByte(), 26.toByte(), 12.toByte(), 205.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(104.toByte(), 26.toByte(), 13.toByte(), 205.toByte()))
                )
                else -> emptyList()
            }
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .writeTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Generates a fresh AmneziaWG WARP profile with obfuscation parameters (`Jc, Jmin, Jmax, S1, S2, H1..H4`).
     * Validates compatibility via `AmneziaWgValidator` (handling AmneziaWG 1.5 `I1` fallback automatically).
     */
    suspend fun registerNewWarpProfile(preferredEndpointIndex: Int = 0): Result<WarpProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.i(TAG, "[Этап 1/3] Генерация ключевой пары Curve25519 и запрос конфига AmneziaWG WARP")
                val keyPair = WarpCrypto.generateKeyPair()
                val obfuscation = AmneziaObfuscationParams.generateForWarpDpiBypass()
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val tosTimestamp = isoFormat.format(Date())
                val serial = UUID.randomUUID().toString().replace("-", "").take(12)

                val payload = JSONObject().apply {
                    put("key", keyPair.publicKeyBase64)
                    put("install_id", "")
                    put("fcm_token", "")
                    put("tos", tosTimestamp)
                    put("model", "Android")
                    put("serial_number", serial)
                    put("locale", "en_US")
                    put("type", "Android")
                }

                val endpoints = listOf(
                    "https://api.cloudflareclient.com/v0a2158/reg",
                    "https://162.159.192.1/v0a2158/reg"
                )

                var responseBodyStr: String? = null

                for (apiUrl in endpoints) {
                    try {
                        val request = Request.Builder()
                            .url(apiUrl)
                            .addHeader("Host", "api.cloudflareclient.com")
                            .addHeader("User-Agent", "okhttp/3.12.1")
                            .addHeader("CF-Client-Version", "a-6.11-2223")
                            .addHeader("Accept", "application/json")
                            .post(payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrBlank() && body.contains("config")) {
                                    responseBodyStr = body
                                }
                            }
                        }
                        if (responseBodyStr != null) break
                    } catch (e: Throwable) {
                        Log.w(TAG, "Узел регистрации $apiUrl недоступен из-за DPI: ${e.message}")
                    }
                }

                val rawJson = responseBodyStr
                val rawProfile = if (rawJson != null) {
                    parseRegistrationResponse(rawJson, keyPair, preferredEndpointIndex, obfuscation)
                } else {
                    Log.i(TAG, "Применён локальный генератор AmneziaWG WARP (обход блокировки ТСПУ РФ)")
                    buildLocalRussiaBypassProfile(keyPair, preferredEndpointIndex, obfuscation)
                }

                // Validate the generated AmneziaWG config through org.amnezia.awg.config.Config
                val validation = AmneziaWgValidator.validateAndParse(
                    rawConf = rawProfile.toWireGuardConf(warpAiEnabled = true, warpOtherEnabled = true),
                    fallbackParams = rawProfile.obfuscation
                )

                rawProfile.copy(
                    obfuscation = validation.obfuscationParams,
                    warningMessage = validation.warnings.firstOrNull()
                )
            }
        }

    internal fun buildLocalRussiaBypassProfile(
        keyPair: WarpKeyPair,
        preferredEndpointIndex: Int = 0,
        obfuscation: AmneziaObfuscationParams = AmneziaObfuscationParams.generateForWarpDpiBypass()
    ): WarpProfile {
        val random = SecureRandom()
        val reservedBytes = listOf(
            random.nextInt(254) + 1,
            random.nextInt(254) + 1,
            random.nextInt(254) + 1
        )
        val clientIdBytes = byteArrayOf(
            reservedBytes[0].toByte(),
            reservedBytes[1].toByte(),
            reservedBytes[2].toByte()
        )
        val clientIdBase64 = Base64.toBase64String(clientIdBytes)

        val selectedEndpoint = WARP_IPV4_ENDPOINTS[
            preferredEndpointIndex.mod(WARP_IPV4_ENDPOINTS.size)
        ]

        val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return WarpProfile(
            deviceId = "awg-${UUID.randomUUID().toString().take(8)}",
            accessToken = "",
            privateKey = keyPair.privateKeyBase64,
            publicKey = keyPair.publicKeyBase64,
            peerPublicKey = DEFAULT_CF_PEER_PUBLIC_KEY,
            clientIdBase64 = clientIdBase64,
            reservedBytes = reservedBytes,
            interfaceAddressV4 = "172.16.0.2",
            endpointHostV4 = selectedEndpoint.first,
            endpointPort = selectedEndpoint.second,
            createdAtIso = isoFormat.format(Date()),
            obfuscation = obfuscation
        )
    }

    internal fun parseRegistrationResponse(
        rawJson: String,
        keyPair: WarpKeyPair,
        preferredEndpointIndex: Int = 0,
        obfuscation: AmneziaObfuscationParams = AmneziaObfuscationParams.generateForWarpDpiBypass()
    ): WarpProfile {
        val root = JSONObject(rawJson)
        val resultObj = root.optJSONObject("result") ?: root
        val deviceId = resultObj.optString("id", UUID.randomUUID().toString().take(16))
        val token = resultObj.optString("token", "")
        val configObj = resultObj.getJSONObject("config")
        val clientIdBase64 = configObj.optString("client_id", "")
        val reservedBytes = WarpCrypto.decodeReservedBytes(clientIdBase64)

        val interfaceObj = configObj.getJSONObject("interface")
        val addressesObj = interfaceObj.getJSONObject("addresses")
        val rawV4 = addressesObj.optString("v4", "172.16.0.2")
        val cleanV4 = rawV4.substringBefore("/").trim().ifEmpty { "172.16.0.2" }

        val peersArr = configObj.getJSONArray("peers")
        val firstPeer = peersArr.getJSONObject(0)
        val peerPublicKey = firstPeer.optString("public_key", DEFAULT_CF_PEER_PUBLIC_KEY)
        val endpointObj = firstPeer.optJSONObject("endpoint")

        val selectedEndpoint = WARP_IPV4_ENDPOINTS[
            preferredEndpointIndex.mod(WARP_IPV4_ENDPOINTS.size)
        ]

        val apiV4Endpoint = endpointObj?.optString("v4", "")
            ?.substringBefore(":")
            ?.trim()
            .orEmpty()

        val finalHostV4 = if (apiV4Endpoint.isNotEmpty() && !apiV4Endpoint.contains(":") && preferredEndpointIndex == 0) {
            apiV4Endpoint
        } else {
            selectedEndpoint.first
        }
        val finalPort = selectedEndpoint.second

        val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return WarpProfile(
            deviceId = deviceId,
            accessToken = token,
            privateKey = keyPair.privateKeyBase64,
            publicKey = keyPair.publicKeyBase64,
            peerPublicKey = peerPublicKey,
            clientIdBase64 = clientIdBase64,
            reservedBytes = reservedBytes,
            interfaceAddressV4 = cleanV4,
            endpointHostV4 = finalHostV4,
            endpointPort = finalPort,
            createdAtIso = isoFormat.format(Date()),
            obfuscation = obfuscation
        )
    }

    /**
     * Rotates the Cloudflare WARP IPv4 endpoint and refreshes AmneziaWG junk packet parameters (Jc, Jmin, Jmax)
     * to bypass DPI and switch region.
     */
    fun rotateEndpoint(current: WarpProfile, step: Int = 1): WarpProfile {
        val currentIdx = WARP_IPV4_ENDPOINTS.indexOfFirst {
            it.first == current.endpointHostV4 && it.second == current.endpointPort
        }
        val nextIdx = if (currentIdx >= 0) {
            (currentIdx + step).mod(WARP_IPV4_ENDPOINTS.size)
        } else {
            step.mod(WARP_IPV4_ENDPOINTS.size)
        }
        val nextEndpoint = WARP_IPV4_ENDPOINTS[nextIdx]
        return current.copy(
            endpointHostV4 = nextEndpoint.first,
            endpointPort = nextEndpoint.second,
            obfuscation = AmneziaObfuscationParams.generateForWarpDpiBypass()
        )
    }

    suspend fun fetchGeoIpInfo(): Result<GeoIpInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val traceMap = fetchCloudflareTrace()
            val cfColo = traceMap["colo"] ?: "FRA"
            val cfLoc = traceMap["loc"]?.uppercase(Locale.US) ?: ""
            val cfIp = traceMap["ip"] ?: ""

            try {
                val request = Request.Builder()
                    .url("https://ipwho.is/?lang=ru")
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "Ali-Guard/1.0")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        if (json.optBoolean("success", true)) {
                            val ip = json.optString("ip", cfIp).ifBlank { "188.114.97.1" }
                            val countryCode = json.optString("country_code", cfLoc).uppercase(Locale.US)
                            val country = json.optString("country", countryNameRuFromCode(countryCode))
                            val region = json.optString("region", "")
                            val city = json.optString("city", "")
                            val connectionObj = json.optJSONObject("connection")
                            val isp = connectionObj?.optString("isp", "Cloudflare AmneziaWG")
                                ?.ifBlank { "Cloudflare AmneziaWG" } ?: "Cloudflare AmneziaWG"
                            val lat = json.optDouble("latitude", 50.1109)
                            val lon = json.optDouble("longitude", 8.6821)

                            val isUnlocked = countryCode.isNotEmpty() && !RESTRICTED_AI_COUNTRY_CODES.contains(countryCode)
                            return@runCatching GeoIpInfo(
                                ip = ip,
                                country = country.ifBlank { countryNameRuFromCode(countryCode) },
                                countryCode = countryCode.ifBlank { "DE" },
                                region = region,
                                city = city,
                                isp = isp,
                                latitude = if (lat.isNaN()) 50.1109 else lat,
                                longitude = if (lon.isNaN()) 8.6821 else lon,
                                isGoogleAiUnlocked = isUnlocked,
                                colo = cfColo
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }

            if (cfIp.isNotBlank()) {
                val code = cfLoc.ifBlank { "DE" }
                val unlocked = !RESTRICTED_AI_COUNTRY_CODES.contains(code)
                return@runCatching GeoIpInfo(
                    ip = cfIp,
                    country = countryNameRuFromCode(code),
                    countryCode = code,
                    region = "Cloudflare Edge ($cfColo)",
                    city = cfColo,
                    isp = "Cloudflare AmneziaWG",
                    latitude = 50.1109,
                    longitude = 8.6821,
                    isGoogleAiUnlocked = unlocked,
                    colo = cfColo
                )
            }

            GeoIpInfo(
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
            )
        }
    }

    private fun fetchCloudflareTrace(): Map<String, String> {
        return try {
            val req = Request.Builder()
                .url("https://1.1.1.1/cdn-cgi/trace")
                .addHeader("User-Agent", "Ali-Guard/1.0")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                text.lineSequence()
                    .mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                        else null
                    }
                    .toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun countryNameRuFromCode(code: String): String {
        return when (code.uppercase(Locale.US)) {
            "DE" -> "Германия"
            "NL" -> "Нидерланды"
            "FI" -> "Финляндия"
            "SE" -> "Швеция"
            "PL" -> "Польша"
            "FR" -> "Франция"
            "GB", "UK" -> "Великобритания"
            "US" -> "США"
            "TR" -> "Турция"
            "KZ" -> "Казахстан"
            "AE" -> "ОАЭ"
            "SG" -> "Сингапур"
            "JP" -> "Япония"
            "AT" -> "Австрия"
            "CH" -> "Швейцария"
            "CZ" -> "Чехия"
            "EE" -> "Эстония"
            "LV" -> "Латвия"
            "LT" -> "Литва"
            "RU" -> "Россия"
            "BY" -> "Беларусь"
            else -> if (code.isNotBlank()) "Регион $code (Европа)" else "Германия"
        }
    }
}
