package com.example

import com.example.routing.SplitTunnelRules
import com.example.warp.WarpCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun curve25519KeyGeneration_producesValidKeys() {
        val kp1 = WarpCrypto.generateKeyPair()
        val kp2 = WarpCrypto.generateKeyPair()

        assertEquals(44, kp1.privateKeyBase64.length)
        assertEquals(44, kp1.publicKeyBase64.length)
        assertEquals(64, kp1.privateKeyHex.length)
        assertEquals(64, kp1.publicKeyHex.length)
        assertNotEquals(kp1.privateKeyBase64, kp2.privateKeyBase64)
        assertNotEquals(kp1.publicKeyBase64, kp2.publicKeyBase64)
    }

    @Test
    fun decodeReservedBytes_parsesClientIdBase64() {
        val reserved = WarpCrypto.decodeReservedBytes("okiN")
        assertEquals(listOf(162, 72, 141), reserved)
        assertEquals("a2488d", WarpCrypto.formatReservedHex(reserved))
    }

    @Test
    fun splitTunnelRules_areStrictlyIPv4AndMatchTargetSubnets() {
        val aiRoutes = SplitTunnelRules.buildRoutes(warpAiEnabled = true, warpOtherEnabled = false)
        val otherRoutes = SplitTunnelRules.buildRoutes(warpAiEnabled = false, warpOtherEnabled = true)
        val bothRoutes = SplitTunnelRules.buildRoutes(warpAiEnabled = true, warpOtherEnabled = true)

        assertTrue(aiRoutes.isNotEmpty())
        assertTrue(otherRoutes.isNotEmpty())
        assertTrue(bothRoutes.size >= aiRoutes.size)

        // Ensure strictly IPv4 (no IPv6 colons)
        assertTrue(bothRoutes.none { it.address.contains(":") })

        // Verify Telegram AS62041 (e.g. 149.154.167.99 and 91.108.5.10) matches Mode B
        assertTrue(SplitTunnelRules.existingStaticMatches("149.154.167.99", warpAiEnabled = false, warpOtherEnabled = true))
        assertTrue(SplitTunnelRules.existingStaticMatches("91.108.5.10", warpAiEnabled = false, warpOtherEnabled = true))

        // Verify WhatsApp Meta AS32934 (e.g. 157.240.12.34) matches Mode B
        assertTrue(SplitTunnelRules.existingStaticMatches("157.240.12.34", warpAiEnabled = false, warpOtherEnabled = true))

        // Verify Google AI / Gemini IPv4 (e.g. 142.250.185.78) matches Mode A
        assertTrue(SplitTunnelRules.existingStaticMatches("142.250.185.78", warpAiEnabled = true, warpOtherEnabled = false))

        // Verify LAN IP does not match either split-tunnel mode
        assertFalse(SplitTunnelRules.existingStaticMatches("192.168.1.10", warpAiEnabled = true, warpOtherEnabled = true))
    }
}
