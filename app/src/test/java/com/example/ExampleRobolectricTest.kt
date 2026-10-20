package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.warp.AmneziaWgValidator
import com.example.warp.CloudflareWarpClient
import com.example.warp.WarpCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Ali-Guard", appName)
    }

    @Test
    fun `amneziaWgConf includes Jc Jmin Jmax S1 S2 H1-H4 and parses via org amnezia awg`() {
        val kp = WarpCrypto.generateKeyPair()
        val sampleJson = """
            {
              "id": "dev-123456",
              "token": "tok-abc",
              "config": {
                "client_id": "okiN",
                "peers": [
                  {
                    "public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                    "endpoint": {
                      "v4": "188.114.97.1:500",
                      "v6": "[2606:4700:d0::a29f:c001]:2408"
                    }
                  }
                ],
                "interface": {
                  "addresses": {
                    "v4": "172.16.0.2",
                    "v6": "2606:4700:110:8f20::1"
                  }
                }
              }
            }
        """.trimIndent()

        val profile = CloudflareWarpClient.parseRegistrationResponse(sampleJson, kp, 0)
        assertEquals("172.16.0.2", profile.interfaceAddressV4)
        assertEquals("188.114.97.1", profile.endpointHostV4)
        assertEquals(listOf(162, 72, 141), profile.reservedBytes)

        val conf = profile.toWireGuardConf(warpAiEnabled = true, warpOtherEnabled = true)
        assertTrue(conf.contains("[Interface]"))
        assertTrue(conf.contains("Jc = "))
        assertTrue(conf.contains("Jmin = "))
        assertTrue(conf.contains("Jmax = "))
        assertTrue(conf.contains("S1 = "))
        assertTrue(conf.contains("S2 = "))
        assertTrue(conf.contains("H1 = "))
        assertTrue(conf.contains("H2 = "))
        assertTrue(conf.contains("H3 = "))
        assertTrue(conf.contains("H4 = "))
        assertTrue(conf.contains("# Reserved = [162, 72, 141]"))
        assertFalse(conf.contains("2606:4700"))
        assertFalse(conf.contains("::/0"))

        val validation = AmneziaWgValidator.validateAndParse(conf, profile.obfuscation)
        assertTrue(validation.isValid)
        assertNotNull(validation.parsedAwgConfig)
        assertEquals(profile.obfuscation.jc, validation.parsedAwgConfig?.`interface`?.junkPacketCount?.orElse(-1))
    }

    @Test
    fun `amneziaWgValidator handles AmneziaWG 1_5 I1 and range headers without crashing`() {
        val kp = WarpCrypto.generateKeyPair()
        val awg15Conf = """
            [Interface]
            PrivateKey = ${kp.privateKeyBase64}
            Address = 172.16.0.2/32, 2606:4700:110::2/128
            DNS = 1.1.1.1, 2606:4700:4700::1111
            MTU = 1280
            Jc = 6
            Jmin = 50
            Jmax = 100
            S1 = 0
            S2 = 0
            H1 = 100000-200000
            H2 = 200001-300000
            H3 = 300001-400000
            H4 = 400001-500000
            I1 = <b 0xc700000001020304>
            I2 = <b 0x00>

            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            AllowedIPs = 142.250.0.0/15, ::/0
            Endpoint = 188.114.97.1:500
            PersistentKeepalive = 25
        """.trimIndent()

        val result = AmneziaWgValidator.validateAndParse(awg15Conf)
        assertTrue(result.isValid)
        assertNotNull(result.parsedAwgConfig)
        assertTrue(result.obfuscationParams.wasDowngradedFrom15)
        assertEquals("<b 0xc700000001020304>", result.obfuscationParams.detectedAwg15I1)
        assertTrue(result.warnings.isNotEmpty())
        assertFalse(result.sanitizedConfText.contains("I1 ="))
        assertFalse(result.sanitizedConfText.contains("2606:4700"))
    }
}
