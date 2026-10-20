package com.example.warp

import android.util.Log
import java.io.BufferedReader
import java.io.StringReader
import java.security.SecureRandom
import org.amnezia.awg.config.Config as AwgConfig

/**
 * Holds the 9 core AmneziaWG 1.0 obfuscation parameters plus optional AmneziaWG 1.5 I1-I5 metadata.
 */
data class AmneziaObfuscationParams(
    val jc: Int = 5,
    val jmin: Int = 40,
    val jmax: Int = 70,
    val s1: Int = 0,
    val s2: Int = 0,
    val h1: Long = 1L,
    val h2: Long = 2L,
    val h3: Long = 3L,
    val h4: Long = 4L,
    val detectedAwg15I1: String? = null,
    val wasDowngradedFrom15: Boolean = false
) {
    fun toSummaryString(): String =
        "Jc=$jc, Jmin=$jmin, Jmax=$jmax, S1=$s1, S2=$s2, H1=$h1, H2=$h2, H3=$h3, H4=$h4"

    companion object {
        private val random = SecureRandom()

        /**
         * Generates randomized DPI-resistant AmneziaWG obfuscation parameters compatible with
         * Cloudflare WARP Anycast nodes in Russia:
         * - Jc: 4..8 pseudorandom junk packets before handshake
         * - Jmin / Jmax: 40..90 byte range for junk packets
         * - S1 / S2: 0 (preserves standard packet length expected by Cloudflare WARP peers)
         * - H1..H4: 1, 2, 3, 4 (preserves WireGuard message type IDs expected by Cloudflare WARP peers)
         */
        fun generateForWarpDpiBypass(): AmneziaObfuscationParams {
            val jc = 4 + random.nextInt(5) // 4..8
            val jmin = 38 + random.nextInt(15) // 38..52
            val jmax = jmin + 25 + random.nextInt(25) // 63..101
            return AmneziaObfuscationParams(
                jc = jc,
                jmin = jmin,
                jmax = jmax,
                s1 = 0,
                s2 = 0,
                h1 = 1L,
                h2 = 2L,
                h3 = 3L,
                h4 = 4L
            )
        }
    }
}

data class AwgValidationResult(
    val isValid: Boolean,
    val sanitizedConfText: String,
    val parsedAwgConfig: AwgConfig?,
    val obfuscationParams: AmneziaObfuscationParams,
    val warnings: List<String>,
    val errorMessage: String? = null
)

/**
 * Validates, sanitizes, and parses AmneziaWG configurations using `com.zaneschepke:amneziawg-android`
 * (`org.amnezia.awg.config.Config`).
 *
 * Handles version incompatibility between AmneziaWG 1.5 (which introduces `I1`..`I5` CPS tags and
 * range headers) and AmneziaWG 1.0 client libraries by detecting `I1`..`I5`, logging a warning,
 * and converting the config into a strict AmneziaWG 1.0 representation so parsing never crashes.
 */
object AmneziaWgValidator {

    private const val TAG = "AliGuardAWG"
    private val AWG_15_KEYS = setOf("i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime")

    /**
     * Validates raw `.conf` text, normalizes AmneziaWG 1.5 parameters if present,
     * ensures all 9 AmneziaWG 1.0 keys (`Jc, Jmin, Jmax, S1, S2, H1, H2, H3, H4`) exist in `[Interface]`,
     * and parses the config via `org.amnezia.awg.config.Config.parse(...)`.
     */
    fun validateAndParse(
        rawConf: String,
        fallbackParams: AmneziaObfuscationParams = AmneziaObfuscationParams()
    ): AwgValidationResult {
        Log.i(TAG, "[Этап 1/3] Начало валидации и парсинга конфига AmneziaWG")
        val warnings = mutableListOf<String>()

        var jc = fallbackParams.jc
        var jmin = fallbackParams.jmin
        var jmax = fallbackParams.jmax
        var s1 = fallbackParams.s1
        var s2 = fallbackParams.s2
        var h1 = fallbackParams.h1
        var h2 = fallbackParams.h2
        var h3 = fallbackParams.h3
        var h4 = fallbackParams.h4
        var detectedI1: String? = null
        var wasDowngraded = false

        val cleanedInterfaceLines = mutableListOf<String>()
        val peerLines = mutableListOf<String>()
        var currentSection = ""

        for (rawLine in rawConf.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            if (line.equals("[Interface]", ignoreCase = true)) {
                currentSection = "Interface"
                continue
            }
            if (line.equals("[Peer]", ignoreCase = true)) {
                currentSection = "Peer"
                peerLines.add("[Peer]")
                continue
            }

            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim()
            val keyLower = key.lowercase()

            if (currentSection == "Interface") {
                when (keyLower) {
                    "jc" -> jc = value.toIntOrNull()?.coerceIn(1, 128) ?: jc
                    "jmin" -> jmin = value.toIntOrNull()?.coerceIn(1, 1200) ?: jmin
                    "jmax" -> jmax = value.toIntOrNull()?.coerceIn(2, 1280) ?: jmax
                    "s1" -> s1 = value.toIntOrNull()?.coerceIn(0, 1132) ?: s1
                    "s2" -> s2 = value.toIntOrNull()?.coerceIn(0, 1188) ?: s2
                    "h1" -> h1 = parseHeaderLong(value, h1, warnings, "H1")
                    "h2" -> h2 = parseHeaderLong(value, h2, warnings, "H2")
                    "h3" -> h3 = parseHeaderLong(value, h3, warnings, "H3")
                    "h4" -> h4 = parseHeaderLong(value, h4, warnings, "H4")
                    in AWG_15_KEYS -> {
                        wasDowngraded = true
                        if (keyLower == "i1") detectedI1 = value
                        val msg = "Обнаружен параметр AmneziaWG 1.5 ($key) — применён fallback до совместимого AmneziaWG 1.0"
                        warnings.add(msg)
                        Log.w(TAG, msg)
                    }
                    "address" -> {
                        // Keep strictly IPv4 addresses only
                        val ipv4Only = value.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.contains(":") }
                            .joinToString(", ")
                        if (ipv4Only.isNotEmpty()) {
                            cleanedInterfaceLines.add("Address = $ipv4Only")
                        }
                    }
                    "dns" -> {
                        val ipv4Dns = value.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.contains(":") }
                            .joinToString(", ")
                        if (ipv4Dns.isNotEmpty()) {
                            cleanedInterfaceLines.add("DNS = $ipv4Dns")
                        }
                    }
                    else -> cleanedInterfaceLines.add("$key = $value")
                }
            } else if (currentSection == "Peer") {
                when (keyLower) {
                    "allowedips" -> {
                        val ipv4Allowed = value.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.contains(":") }
                            .joinToString(", ")
                        if (ipv4Allowed.isNotEmpty()) {
                            peerLines.add("AllowedIPs = $ipv4Allowed")
                        }
                    }
                    "endpoint" -> {
                        if (!value.contains("[")) {
                            peerLines.add("Endpoint = $value")
                        }
                    }
                    else -> peerLines.add("$key = $value")
                }
            }
        }

        // Enforce AmneziaWG mathematical constraints
        if (jmax <= jmin) {
            jmax = (jmin + 30).coerceAtMost(1280)
            warnings.add("Скорректирован Jmax ($jmax > Jmin=$jmin)")
        }
        if (s1 + 148 == s2 + 92) {
            s2 += 1
            warnings.add("Скорректирован S2 для соблюдения условия S1+148 != S2+92")
        }
        // Ensure H1..H4 are unique
        val uniqueHeaders = mutableSetOf<Long>()
        val resolvedHeaders = listOf(h1, h2, h3, h4).mapIndexed { idx, h ->
            var candidate = h.coerceAtLeast(1L)
            while (!uniqueHeaders.add(candidate)) {
                candidate = (idx + 1).toLong() + uniqueHeaders.size
            }
            candidate
        }
        h1 = resolvedHeaders[0]
        h2 = resolvedHeaders[1]
        h3 = resolvedHeaders[2]
        h4 = resolvedHeaders[3]

        val validatedParams = AmneziaObfuscationParams(
            jc = jc,
            jmin = jmin,
            jmax = jmax,
            s1 = s1,
            s2 = s2,
            h1 = h1,
            h2 = h2,
            h3 = h3,
            h4 = h4,
            detectedAwg15I1 = detectedI1,
            wasDowngradedFrom15 = wasDowngraded
        )

        val normalizedConf = buildString {
            appendLine("[Interface]")
            cleanedInterfaceLines.forEach { appendLine(it) }
            appendLine("Jc = ${validatedParams.jc}")
            appendLine("Jmin = ${validatedParams.jmin}")
            appendLine("Jmax = ${validatedParams.jmax}")
            appendLine("S1 = ${validatedParams.s1}")
            appendLine("S2 = ${validatedParams.s2}")
            appendLine("H1 = ${validatedParams.h1}")
            appendLine("H2 = ${validatedParams.h2}")
            appendLine("H3 = ${validatedParams.h3}")
            appendLine("H4 = ${validatedParams.h4}")
            appendLine()
            peerLines.forEach { appendLine(it) }
        }

        return try {
            val parsed = AwgConfig.parse(BufferedReader(StringReader(normalizedConf)))
            Log.i(
                TAG,
                "[Этап 2/3] Конфиг AmneziaWG успешно распарсен библиотекой org.amnezia.awg (${validatedParams.toSummaryString()})"
            )
            AwgValidationResult(
                isValid = true,
                sanitizedConfText = normalizedConf,
                parsedAwgConfig = parsed,
                obfuscationParams = validatedParams,
                warnings = warnings
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Ошибка парсинга AmneziaWG конфига: ${e.message}", e)
            AwgValidationResult(
                isValid = false,
                sanitizedConfText = normalizedConf,
                parsedAwgConfig = null,
                obfuscationParams = validatedParams,
                warnings = warnings,
                errorMessage = e.localizedMessage ?: "Ошибка формата AmneziaWG"
            )
        }
    }

    private fun parseHeaderLong(
        rawValue: String,
        defaultVal: Long,
        warnings: MutableList<String>,
        headerName: String
    ): Long {
        // AmneziaWG 1.5 sometimes outputs ranges like "100000-200000" for H1..H4
        if (rawValue.contains("-")) {
            val lower = rawValue.substringBefore("-").trim().toLongOrNull()
            if (lower != null && lower > 0) {
                warnings.add("$headerName задан диапазоном ($rawValue) в формате AWG 1.5 — выбрано фиксированное значение $lower")
                return lower
            }
        }
        return rawValue.toLongOrNull()?.coerceAtLeast(1L) ?: defaultVal
    }
}
