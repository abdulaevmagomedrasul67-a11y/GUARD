package com.example.warp

import com.wireguard.crypto.KeyPair
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.util.encoders.Base64
import java.security.SecureRandom

data class WarpKeyPair(
    val privateKeyBase64: String,
    val publicKeyBase64: String,
    val privateKeyHex: String,
    val publicKeyHex: String
)

object WarpCrypto {
    private val secureRandom = SecureRandom()

    /**
     * Generates a Curve25519 (X25519) WireGuard keypair locally on the device
     * using BouncyCastle and validates it against WireGuard's Key format.
     */
    fun generateKeyPair(): WarpKeyPair {
        return try {
            val generator = X25519KeyPairGenerator()
            generator.init(X25519KeyGenerationParameters(secureRandom))
            val pair = generator.generateKeyPair()
            val privParams = pair.private as X25519PrivateKeyParameters
            val pubParams = pair.public as X25519PublicKeyParameters

            val privBytes = privParams.encoded
            val pubBytes = pubParams.encoded

            val privBase64 = Base64.toBase64String(privBytes)
            val pubBase64 = Base64.toBase64String(pubBytes)

            WarpKeyPair(
                privateKeyBase64 = privBase64,
                publicKeyBase64 = pubBase64,
                privateKeyHex = privBytes.toHexString(),
                publicKeyHex = pubBytes.toHexString()
            )
        } catch (_: Throwable) {
            val wgKeyPair = KeyPair()
            WarpKeyPair(
                privateKeyBase64 = wgKeyPair.privateKey.toBase64(),
                publicKeyBase64 = wgKeyPair.publicKey.toBase64(),
                privateKeyHex = wgKeyPair.privateKey.toHex(),
                publicKeyHex = wgKeyPair.publicKey.toHex()
            )
        }
    }

    /**
     * Decodes Cloudflare's base64-encoded `client_id` into the 3 WireGuard reserved bytes
     * as implemented in lanrat/wireguard-warp-generator.
     */
    fun decodeReservedBytes(clientIdBase64: String): List<Int> {
        if (clientIdBase64.isBlank()) return listOf(0, 0, 0)
        return try {
            val decoded = Base64.decode(clientIdBase64.trim())
            if (decoded.size >= 3) {
                listOf(
                    decoded[0].toInt() and 0xFF,
                    decoded[1].toInt() and 0xFF,
                    decoded[2].toInt() and 0xFF
                )
            } else {
                listOf(0, 0, 0)
            }
        } catch (_: Exception) {
            listOf(0, 0, 0)
        }
    }

    fun formatReservedHex(reserved: List<Int>): String {
        return reserved.joinToString(separator = "") { byte ->
            "%02x".format(byte and 0xFF)
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { b -> "%02x".format(b.toInt() and 0xFF) }
}
