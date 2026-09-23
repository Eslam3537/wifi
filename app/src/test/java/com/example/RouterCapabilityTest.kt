package com.example

import com.example.domain.router.HuaweiRouterController
import com.example.domain.router.TPLinkRouterController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterCapabilityTest {

    @Test
    fun testUnreachableGatewayReturnsProperCapability() = runBlocking {
        val controller = HuaweiRouterController()
        // Probing an invalid loopback/unbound address should fail gracefully without throwing
        val cap = controller.probeCapabilities("127.0.0.1:54321")
        assertNotNull(cap)
        assertFalse(cap.isSupported)
        assertTrue(cap.unsupportedReasons.isNotEmpty())
    }

    @Test
    fun testTPLinkControllerReturnsUnsupported() = runBlocking {
        val controller = TPLinkRouterController()
        val cap = controller.probeCapabilities("127.0.0.1:54321")
        assertNotNull(cap)
        assertFalse(cap.isSupported)
    }

    @Test
    fun testBlockDeviceFailsHonestlyWithoutFakeSuccess() = runBlocking {
        val controller = HuaweiRouterController()
        val result = controller.blockDevice("AA:BB:CC:DD:EE:FF", "192.168.1.50")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported") == true)
    }
}
