package com.example.warp

import com.example.routing.CidrRoute
import com.example.routing.SplitTunnelRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
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
    val createdAtIso: String
) {
    val endpointString: String
        get() = "$endpointHostV4:$endpointPort"

    /**
     * Assembles a standard WireGuard `.conf` configuration string (IPv4-only).
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
            // Default IPv4 split routes when previewing .conf with both modes enabled
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
 * Native Kotlin implementation of:
 * 1. `lanrat/wireguard-warp-generator`: registers a Curve25519 public key with
 *    `https://api.cloudflareclient.com/v0a2158/reg` and parses the IPv4 address,
 *    peer key, endpoint, and 3 reserved bytes (`client_id`).
 * 2. `vps8899/warp-google-unlock`: checks if the assigned WARP exit IP / region is blocked
 *    by Google AI (e.g. RU/BY/CN/HK) and rotates Cloudflare Anycast IPv4 endpoints or
 *    re-registers the WARP peer to acquire a clean IP.
 * 3. IPv4-only geolocation via `https://ipwho.is/` and Cloudflare trace.
 */
object CloudflareWarpClient {

    private const val DEFAULT_CF_PEER_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    // Verified IPv4 Cloudflare WARP Anycast endpoints for endpoint rotation (vps8899/warp-google-unlock)
    val WARP_IPV4_ENDPOINTS = listOf(
        "162.159.192.1" to 2408,
        "162.159.193.1" to 2408,
        "162.159.195.1" to 2408,
        "188.114.96.1" to 2408,
        "188.114.97.1" to 2408,
        "188.114.98.1" to 2408,
        "188.114.99.1" to 2408,
        "162.159.192.7" to 2408,
        "162.159.193.9" to 2408
    )

    // Regions where Google Gemini / AI Studio blocks access (used by vps8899/warp-google-unlock logic)
    private val RESTRICTED_AI_COUNTRY_CODES = setOf("RU", "BY", "CN", "HK", "IR", "KP", "SY", "CU")

    // Strictly IPv4-only DNS resolver to prevent any IPv6 socket attempts
    val ipv4OnlyDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            return v4.ifEmpty { all }
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4OnlyDns)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Generates a fresh Curve25519 keypair and registers it with the official Cloudflare WARP API.
     */
    suspend fun registerNewWarpProfile(preferredEndpointIndex: Int = 0): Result<WarpProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val keyPair = WarpCrypto.generateKeyPair()
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
                    "https://api.cloudflareclient.com/v0a2483/reg",
                    "https://api.cloudflareclient.com/v0a1922/reg"
                )

                var responseBodyStr: String? = null
                var lastError: Throwable? = null

                for (apiUrl in endpoints) {
                    try {
                        val request = Request.Builder()
                            .url(apiUrl)
                            .addHeader("User-Agent", "okhttp/3.12.1")
                            .addHeader("CF-Client-Version", "a-6.11-2223")
                            .addHeader("Accept", "application/json")
                            .post(payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrBlank()) {
                                    responseBodyStr = body
                                }
                            } else {
                                lastError = IllegalStateException("Cloudflare API HTTP ${response.code}")
                            }
                        }
                        if (responseBodyStr != null) break
                    } catch (e: Throwable) {
                        lastError = e
                    }
                }

                val rawJson = responseBodyStr ?: throw (lastError ?: IllegalStateException("Не удалось связаться с api.cloudflareclient.com"))
                parseRegistrationResponse(rawJson, keyPair, preferredEndpointIndex)
            }
        }

    internal fun parseRegistrationResponse(
        rawJson: String,
        keyPair: WarpKeyPair,
        preferredEndpointIndex: Int = 0
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
        // Explicitly take IPv4 only and strip any CIDR suffix if present
        val rawV4 = addressesObj.optString("v4", "172.16.0.2")
        val cleanV4 = rawV4.substringBefore("/").trim().ifEmpty { "172.16.0.2" }

        val peersArr = configObj.getJSONArray("peers")
        val firstPeer = peersArr.getJSONObject(0)
        val peerPublicKey = firstPeer.optString("public_key", DEFAULT_CF_PEER_PUBLIC_KEY)
        val endpointObj = firstPeer.optJSONObject("endpoint")

        val selectedEndpoint = WARP_IPV4_ENDPOINTS[
            preferredEndpointIndex.mod(WARP_IPV4_ENDPOINTS.size)
        ]

        // Extract pure IPv4 address from endpoint.v4 if provided, otherwise use verified IPv4 Anycast pool
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
            createdAtIso = isoFormat.format(Date())
        )
    }

    /**
     * Rotates the Cloudflare WARP IPv4 endpoint on an existing profile (and optionally re-registers)
     * following `vps8899/warp-google-unlock` to acquire a clean non-RU IP for Google AI.
     */
    fun rotateEndpoint(current: WarpProfile, step: Int = 1): WarpProfile {
        val currentIdx = WARP_IPV4_ENDPOINTS.indexOfFirst { it.first == current.endpointHostV4 }
        val nextIdx = if (currentIdx >= 0) {
            (currentIdx + step).mod(WARP_IPV4_ENDPOINTS.size)
        } else {
            step.mod(WARP_IPV4_ENDPOINTS.size)
        }
        val nextEndpoint = WARP_IPV4_ENDPOINTS[nextIdx]
        return current.copy(
            endpointHostV4 = nextEndpoint.first,
            endpointPort = nextEndpoint.second
        )
    }

    /**
     * Queries `ipwho.is` (keyless IPv4 geolocation API) and Cloudflare trace
     * to determine the current country/region and whether Google AI is unlocked.
     */
    suspend fun fetchGeoIpInfo(): Result<GeoIpInfo> = withContext(Dispatchers.IO) {
        runCatching {
            // Also fetch Cloudflare trace colo in parallel/sequence for accurate WARP POP detection
            val traceMap = fetchCloudflareTrace()
            val cfColo = traceMap["colo"] ?: "FRA"
            val cfLoc = traceMap["loc"]?.uppercase(Locale.US) ?: ""
            val cfIp = traceMap["ip"] ?: ""

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
                        val ip = json.optString("ip", cfIp).ifBlank { "172.16.0.2" }
                        val country = json.optString("country", countryNameRuFromCode(cfLoc))
                        val countryCode = json.optString("country_code", cfLoc).uppercase(Locale.US)
                        val region = json.optString("region", "")
                        val city = json.optString("city", "")
                        val connectionObj = json.optJSONObject("connection")
                        val isp = connectionObj?.optString("isp", "Cloudflare WARP")
                            ?.ifBlank { "Cloudflare WARP" } ?: "Cloudflare WARP"
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

            // Fallback to Cloudflare 1.1.1.1 trace if ipwho.is rate-limits
            if (cfIp.isNotBlank()) {
                val code = cfLoc.ifBlank { "DE" }
                val unlocked = !RESTRICTED_AI_COUNTRY_CODES.contains(code)
                return@runCatching GeoIpInfo(
                    ip = cfIp,
                    country = countryNameRuFromCode(code),
                    countryCode = code,
                    region = "Cloudflare Edge ($cfColo)",
                    city = cfColo,
                    isp = "Cloudflare, Inc.",
                    latitude = 50.1109,
                    longitude = 8.6821,
                    isGoogleAiUnlocked = unlocked,
                    colo = cfColo
                )
            }

            throw IllegalStateException("Не удалось получить данные геолокации")
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
            "RU" -> "Россия (Ограничено для AI)"
            "BY" -> "Беларусь (Ограничено для AI)"
            else -> if (code.isNotBlank()) "Регион $code (Европа)" else "Германия"
        }
    }
}
