package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.protection.*
import com.example.model.DeviceAccessStatus
import com.example.model.DeviceSpeedStatus
import com.example.model.DiscoveredDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkDeviceControlTest {

    private lateinit var context: Context
    private lateinit var ruleStateManager: RuleStateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("netmanager_enforced_rules_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ruleStateManager = RuleStateManager(context)
    }

    @Test
    fun testTrafficControlEngineConstantsAndClassId() {
        assertEquals("1:", TrafficControlEngine.TC_ROOT_HANDLE)
        assertEquals("30", TrafficControlEngine.DEFAULT_CLASS_ID)
        assertEquals(155, TrafficControlEngine.computeClassId("192.168.1.145"))
    }

    @Test
    fun testDeterministicRulePrecedenceHierarchy() {
        val targetIp = "192.168.1.145"

        // 1. Default state -> null (Unlimited)
        assertNull(ruleStateManager.getEffectiveBandwidthLimit(targetIp, isProtected = false))

        // 2. Apply Global Limit -> Should be global limit
        ruleStateManager.registerGlobalRateLimit(5000L) // 5 Mbps
        assertEquals(5000L, ruleStateManager.getEffectiveBandwidthLimit(targetIp, isProtected = false))

        // 3. Apply Device-Specific Limit (e.g. 1 Mbps) -> Overrides global limit
        ruleStateManager.registerRateLimitedDevice(
            ip = targetIp,
            mac = "00:11:22:33:44:55",
            name = "Smart TV",
            rateKbps = 1000L
        )
        assertEquals(1000L, ruleStateManager.getEffectiveBandwidthLimit(targetIp, isProtected = false))

        // 4. Protected / Safety Exclusion -> Always Unlimited (null), overriding device limit and global limit
        assertNull(ruleStateManager.getEffectiveBandwidthLimit(targetIp, isProtected = true))

        // 5. Remove device specific limit -> Falls back to global limit
        ruleStateManager.registerRemovedRateLimit(targetIp)
        assertEquals(5000L, ruleStateManager.getEffectiveBandwidthLimit(targetIp, isProtected = false))

        // 6. Remove global limit -> Falls back to default null (Unlimited)
        ruleStateManager.removeGlobalRateLimit()
        assertNull(ruleStateManager.getEffectiveBandwidthLimit(targetIp, isProtected = false))
    }

    @Test
    fun testRuleStateManagerDeviceBlockingAndRateLimiting() {
        assertFalse(ruleStateManager.hasActiveRestrictions())

        // Block a device
        ruleStateManager.registerBlockedDevice(
            ip = "192.168.1.100",
            mac = "AA:BB:CC:11:22:33",
            name = "Intruder Device",
            backend = EnforcementBackend.ROOT_IPTABLES
        )
        assertTrue(ruleStateManager.hasActiveRestrictions())
        assertEquals(1, ruleStateManager.activeRules.value.size)
        assertEquals(EnforcementType.DEVICE_BLOCK, ruleStateManager.activeRules.value[0].type)

        // Rate limit another device
        ruleStateManager.registerRateLimitedDevice(
            ip = "192.168.1.101",
            mac = "AA:BB:CC:11:22:44",
            name = "Guest Phone",
            rateKbps = 2000L
        )
        assertEquals(2, ruleStateManager.activeRules.value.size)

        // Unblock first device
        ruleStateManager.registerUnblockedDevice("192.168.1.100")
        assertEquals(1, ruleStateManager.activeRules.value.size)
        assertEquals(EnforcementType.BANDWIDTH_LIMIT, ruleStateManager.activeRules.value[0].type)
        assertEquals(2000L, ruleStateManager.activeRules.value[0].bandwidthLimitKbps)

        // Remove rate limit on second device
        ruleStateManager.registerRemovedRateLimit("192.168.1.101")
        assertEquals(0, ruleStateManager.activeRules.value.size)
        assertFalse(ruleStateManager.hasActiveRestrictions())
    }

    @Test
    fun testDiscoveredDeviceSpeedStatusAndFormatting() {
        val normalDevice = DiscoveredDevice(
            ip = "192.168.1.50",
            bandwidthLimitKbps = null,
            accessStatus = DeviceAccessStatus.ONLINE
        )
        assertEquals(DeviceSpeedStatus.UNLIMITED, normalDevice.speedStatus)
        assertEquals("Unlimited", normalDevice.formattedBandwidthLimit)

        val limitedDevice = DiscoveredDevice(
            ip = "192.168.1.51",
            bandwidthLimitKbps = 10000L,
            accessStatus = DeviceAccessStatus.ONLINE
        )
        assertEquals(DeviceSpeedStatus.LIMITED, limitedDevice.speedStatus)
        assertEquals("10 Mbps", limitedDevice.formattedBandwidthLimit)

        val smallLimitDevice = DiscoveredDevice(
            ip = "192.168.1.52",
            bandwidthLimitKbps = 512L,
            accessStatus = DeviceAccessStatus.ONLINE
        )
        assertEquals(DeviceSpeedStatus.LIMITED, smallLimitDevice.speedStatus)
        assertEquals("512 Kbps", smallLimitDevice.formattedBandwidthLimit)

        val blockedDevice = DiscoveredDevice(
            ip = "192.168.1.53",
            bandwidthLimitKbps = 10000L,
            accessStatus = DeviceAccessStatus.BLOCKED
        )
        assertEquals(DeviceSpeedStatus.BLOCKED, blockedDevice.speedStatus)
    }
}
