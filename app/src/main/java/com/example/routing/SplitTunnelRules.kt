package com.example.routing

import java.net.Inet4Address
import java.net.InetAddress

data class CidrRoute(
    val address: String,
    val prefixLength: Int,
    val category: String
) {
    val cidrNotation: String
        get() = "$address/$prefixLength"

    val networkInt: Int by lazy {
        val parts = address.split(".")
        if (parts.size == 4) {
            ((parts[0].toInt() and 0xFF) shl 24) or
                ((parts[1].toInt() and 0xFF) shl 16) or
                ((parts[2].toInt() and 0xFF) shl 8) or
                (parts[3].toInt() and 0xFF)
        } else {
            0
        }
    }

    val maskInt: Int by lazy {
        if (prefixLength <= 0) 0
        else if (prefixLength >= 32) -1
        else (-1 shl (32 - prefixLength))
    }

    fun matches(ipv4Int: Int): Boolean {
        return (ipv4Int and maskInt) == (networkInt and maskInt)
    }
}

/**
 * IPv4-only Split Tunneling rules for Ali-Guard:
 * - Mode A ("WARP AI"): Google Gemini, Google AI Studio, googleapis.com, googleusercontent.com,
 *   gstatic.com, 1e100.net, google.com IPv4 CIDR ranges.
 * - Mode B ("WARP Остальное"): WhatsApp (Meta AS32934), Telegram (AS62041 & related),
 *   YouTube (googlevideo.com, ytimg.com, youtube.com CIDR ranges from nellimonix/warp-config-generator-vercel).
 */
object SplitTunnelRules {

    val AI_TARGET_DOMAINS = listOf(
        "gemini.google.com",
        "aistudio.google.com",
        "generativelanguage.googleapis.com",
        "alkalimakersuite-pa.clients6.google.com",
        "ai.google.dev",
        "googleapis.com",
        "googleusercontent.com",
        "gstatic.com",
        "1e100.net",
        "google.com"
    )

    val OTHER_TARGET_DOMAINS = listOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "ytimg.com",
        "i.ytimg.com",
        "googlevideo.com",
        "web.whatsapp.com",
        "whatsapp.com",
        "t.me",
        "web.telegram.org"
    )

    // Mode A: WARP for AI (Google AI Studio, Gemini, googleapis, gstatic, 1e100.net, googleusercontent IPv4 blocks)
    val WARP_AI_CIDRS: List<CidrRoute> = listOf(
        // Cloudflare DNS (IPv4-only) for encrypted tunnel resolution
        CidrRoute("1.1.1.1", 32, "DNS"),
        CidrRoute("1.0.0.1", 32, "DNS"),
        // Google Core & 1e100.net / googleapis / gstatic / Gemini / AI Studio IPv4 ranges
        CidrRoute("34.64.0.0", 10, "Google AI Cloud"),
        CidrRoute("34.128.0.0", 10, "Google AI Cloud"),
        CidrRoute("35.184.0.0", 13, "Google AI Studio"),
        CidrRoute("35.190.0.0", 16, "Google AI Edge"),
        CidrRoute("35.192.0.0", 12, "googleapis.com"),
        CidrRoute("35.208.0.0", 12, "googleusercontent.com"),
        CidrRoute("35.224.0.0", 12, "Google Cloud AI"),
        CidrRoute("35.240.0.0", 13, "Google Cloud AI"),
        CidrRoute("64.233.160.0", 19, "1e100.net"),
        CidrRoute("66.102.0.0", 20, "google.com"),
        CidrRoute("66.249.64.0", 19, "google.com"),
        CidrRoute("72.14.192.0", 18, "gstatic.com"),
        CidrRoute("74.125.0.0", 16, "googleapis.com"),
        CidrRoute("108.177.0.0", 17, "1e100.net"),
        CidrRoute("142.250.0.0", 15, "gemini.google.com"),
        CidrRoute("172.217.0.0", 16, "google.com"),
        CidrRoute("172.253.0.0", 16, "googleapis.com"),
        CidrRoute("173.194.0.0", 16, "gstatic.com"),
        CidrRoute("209.85.128.0", 17, "googleusercontent.com"),
        CidrRoute("216.58.192.0", 19, "1e100.net"),
        CidrRoute("216.239.32.0", 19, "google.com")
    )

    // Mode B: WARP for Other (Telegram AS62041, WhatsApp Meta AS32934, YouTube googlevideo/ytimg)
    val WARP_OTHER_CIDRS: List<CidrRoute> = listOf(
        // Cloudflare DNS (IPv4-only)
        CidrRoute("1.1.1.1", 32, "DNS"),
        CidrRoute("1.0.0.1", 32, "DNS"),

        // Telegram (AS62041, AS62014, AS44907, AS59930, AS211157)
        CidrRoute("91.105.192.0", 23, "Telegram"),
        CidrRoute("91.108.4.0", 22, "Telegram"),
        CidrRoute("91.108.8.0", 22, "Telegram"),
        CidrRoute("91.108.12.0", 22, "Telegram"),
        CidrRoute("91.108.16.0", 22, "Telegram"),
        CidrRoute("91.108.20.0", 22, "Telegram"),
        CidrRoute("91.108.56.0", 22, "Telegram"),
        CidrRoute("91.108.58.0", 23, "Telegram"),
        CidrRoute("95.161.64.0", 20, "Telegram"),
        CidrRoute("149.154.160.0", 20, "Telegram"),
        CidrRoute("185.76.151.0", 24, "Telegram"),

        // WhatsApp / Meta (AS32934)
        CidrRoute("31.13.24.0", 21, "WhatsApp"),
        CidrRoute("31.13.64.0", 18, "WhatsApp"),
        CidrRoute("45.64.40.0", 22, "WhatsApp"),
        CidrRoute("57.144.0.0", 14, "WhatsApp"),
        CidrRoute("66.220.144.0", 20, "WhatsApp"),
        CidrRoute("69.63.176.0", 20, "WhatsApp"),
        CidrRoute("69.171.224.0", 19, "WhatsApp"),
        CidrRoute("74.119.76.0", 22, "WhatsApp"),
        CidrRoute("102.132.96.0", 20, "WhatsApp"),
        CidrRoute("103.4.96.0", 22, "WhatsApp"),
        CidrRoute("129.134.0.0", 17, "WhatsApp"),
        CidrRoute("157.240.0.0", 16, "WhatsApp"),
        CidrRoute("163.70.128.0", 17, "WhatsApp"),
        CidrRoute("173.252.64.0", 18, "WhatsApp"),
        CidrRoute("179.60.192.0", 22, "WhatsApp"),
        CidrRoute("185.60.216.0", 22, "WhatsApp"),
        CidrRoute("185.89.216.0", 22, "WhatsApp"),
        CidrRoute("204.15.20.0", 22, "WhatsApp"),

        // YouTube (youtube.com, googlevideo.com, ytimg.com)
        CidrRoute("64.15.112.0", 20, "YouTube"),
        CidrRoute("74.125.0.0", 16, "YouTube"),
        CidrRoute("108.177.0.0", 17, "YouTube"),
        CidrRoute("142.250.0.0", 15, "YouTube"),
        CidrRoute("172.217.0.0", 16, "YouTube"),
        CidrRoute("172.253.0.0", 16, "YouTube"),
        CidrRoute("173.194.0.0", 16, "YouTube"),
        CidrRoute("208.65.152.0", 22, "YouTube"),
        CidrRoute("208.117.224.0", 19, "YouTube"),
        CidrRoute("209.85.128.0", 17, "YouTube"),
        CidrRoute("216.58.192.0", 19, "YouTube"),
        CidrRoute("216.73.80.0", 20, "YouTube")
    )

    /**
     * Builds deduplicated IPv4-only CIDR routes for the active mode(s).
     */
    fun buildRoutes(
        warpAiEnabled: Boolean,
        warpOtherEnabled: Boolean,
        dynamicResolvedIps: List<CidrRoute> = emptyList()
    ): List<CidrRoute> {
        val combined = mutableListOf<CidrRoute>()
        if (warpAiEnabled) {
            combined.addAll(WARP_AI_CIDRS)
        }
        if (warpOtherEnabled) {
            combined.addAll(WARP_OTHER_CIDRS)
        }
        if (warpAiEnabled || warpOtherEnabled) {
            combined.addAll(dynamicResolvedIps)
        }
        // Strictly filter to valid IPv4 addresses only (no IPv6 colons)
        return combined
            .filter { !it.address.contains(":") && it.prefixLength in 1..32 }
            .distinctBy { "${it.address}/${it.prefixLength}" }
    }

    /**
     * Resolves current IPv4 records for active domains so any edge CDN / GGC IP
     * not in static CIDR blocks is also routed into the WARP tunnel.
     */
    fun resolveDynamicDomainRoutes(
        warpAiEnabled: Boolean,
        warpOtherEnabled: Boolean
    ): List<CidrRoute> {
        val domains = buildList {
            if (warpAiEnabled) addAll(AI_TARGET_DOMAINS)
            if (warpOtherEnabled) addAll(OTHER_TARGET_DOMAINS)
        }
        val discovered = mutableListOf<CidrRoute>()
        for (domain in domains) {
            try {
                val addresses = InetAddress.getAllByName(domain)
                for (addr in addresses) {
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!existingStaticMatches(ip, warpAiEnabled, warpOtherEnabled)) {
                            // Route the /24 subnet of the discovered edge server
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                val subnet24 = "${parts[0]}.${parts[1]}.${parts[2]}.0"
                                discovered.add(CidrRoute(subnet24, 24, domain))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore offline or blocked DNS lookups; static CIDRs still cover core ranges
            }
        }
        return discovered.distinctBy { it.cidrNotation }
    }

    fun existingStaticMatches(
        ipv4String: String,
        warpAiEnabled: Boolean,
        warpOtherEnabled: Boolean
    ): Boolean {
        val ipv4Int = ipv4ToInt(ipv4String) ?: return false
        val staticList = buildRoutes(warpAiEnabled, warpOtherEnabled)
        return staticList.any { it.matches(ipv4Int) }
    }

    fun ipv4ToInt(ipv4: String): Int? {
        val parts = ipv4.split(".")
        if (parts.size != 4) return null
        return try {
            ((parts[0].toInt() and 0xFF) shl 24) or
                ((parts[1].toInt() and 0xFF) shl 16) or
                ((parts[2].toInt() and 0xFF) shl 8) or
                (parts[3].toInt() and 0xFF)
        } catch (_: NumberFormatException) {
            null
        }
    }
}
