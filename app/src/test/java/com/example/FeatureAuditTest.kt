package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.capability.SystemCapabilityEvaluator
import com.example.domain.discovery.NetworkUtils
import com.example.domain.security.ProtectedDevicesManager
import com.example.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeatureAuditTest {

    @Test
    fun testProtectedDevicesManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ProtectedDevicesManager(context)

        assertFalse(manager.isProtected("192.168.1.55"))
        manager.setProtected("192.168.1.55", true)
        assertTrue(manager.isProtected("192.168.1.55"))

        val all = manager.getAllProtectedIps()
        assertTrue(all.contains("192.168.1.55"))

        manager.setProtected("192.168.1.55", false)
        assertFalse(manager.isProtected("192.168.1.55"))
    }

    @Test
    fun testDeduplicateDevicesPreservesProtection() {
        val existing = listOf(
            DiscoveredDevice(
                ip = "192.168.1.20",
                mac = "AA:BB:CC:DD:EE:01",
                vendor = "TestVendor",
                deviceType = DeviceType.PHONE,
                status = DeviceStatus.ONLINE,
                isGateway = false,
                isCurrentDevice = false,
                isProtected = true,
                accessStatus = DeviceAccessStatus.BLOCKED
            )
        )

        val fresh = listOf(
            DiscoveredDevice(
                ip = "192.168.1.20",
                mac = null,
                vendor = "Unknown",
                deviceType = DeviceType.UNKNOWN,
                status = DeviceStatus.ONLINE,
                isGateway = false,
                isCurrentDevice = false,
                isProtected = false
            )
        )

        val merged = NetworkUtils.deduplicateDevices(existing, fresh)
        assertEquals(1, merged.size)
        val dev = merged.first()
        assertTrue(dev.isProtected)
        assertEquals(DeviceAccessStatus.BLOCKED, dev.accessStatus)
        assertEquals("AA:BB:CC:DD:EE:01", dev.mac)
    }

    @Test
    fun testSystemCapabilityEvaluator() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = SystemCapabilityEvaluator.evaluate(
            context = context,
            routerCapability = null,
            networkInfo = null
        )

        assertNotNull(report)
        assertNotNull(report.root)
        assertNotNull(report.kernelSu)
        assertEquals(CapabilityState.UNSUPPORTED, report.routerControl)
        assertEquals(CapabilityState.UNSUPPORTED, report.blockDevice)
    }
}
