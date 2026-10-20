package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.warp.CloudflareWarpClient
import com.example.warp.WarpCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `warpConfFormatting excludes IPv6 and includes Reserved bytes`() {
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
                      "v4": "162.159.192.1:2408",
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
        assertEquals("162.159.192.1", profile.endpointHostV4)
        assertEquals(listOf(162, 72, 141), profile.reservedBytes)

        val conf = profile.toWireGuardConf(warpAiEnabled = true, warpOtherEnabled = true)
        assertTrue(conf.contains("[Interface]"))
        assertTrue(conf.contains("Address = 172.16.0.2/32"))
        assertTrue(conf.contains("DNS = 1.1.1.1, 1.0.0.1"))
        assertTrue(conf.contains("# Reserved = [162, 72, 141]"))
        assertTrue(conf.contains("[Peer]"))
        assertFalse(conf.contains("2606:4700"))
        assertFalse(conf.contains("::/0"))
    }
}
