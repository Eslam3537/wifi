package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.protection.EnforcedRule
import com.example.domain.protection.EnforcementBackend
import com.example.domain.protection.EnforcementType
import com.example.domain.protection.FirewallEnforcementEngine
import com.example.domain.protection.RuleStateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartExitAndProtectionTest {

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
    fun testRuleStateManagerRegistrationAndQuery() {
        assertFalse(ruleStateManager.hasActiveRestrictions())

        ruleStateManager.registerBlockedDevice(
            ip = "192.168.1.105",
            mac = "00:11:22:33:44:55",
            name = "Test Laptop",
            backend = EnforcementBackend.ROUTER_API
        )

        assertTrue(ruleStateManager.hasActiveRestrictions())
        val rules = ruleStateManager.activeRules.value
        assertEquals(1, rules.size)
        assertEquals("192.168.1.105", rules[0].targetIdentifier)
        assertEquals(EnforcementType.DEVICE_BLOCK, rules[0].type)
        assertEquals(EnforcementBackend.ROUTER_API, rules[0].backend)

        ruleStateManager.registerUnblockedDevice("192.168.1.105")
        assertFalse(ruleStateManager.hasActiveRestrictions())
        assertEquals(0, ruleStateManager.activeRules.value.size)
    }

    @Test
    fun testMultipleRulesAndBackgroundFlag() {
        ruleStateManager.registerBlockedDevice("192.168.1.50", "AA:BB:CC:DD:EE:01", "Device 1", EnforcementBackend.ROOT_IPTABLES)
        ruleStateManager.registerBlockedDevice("192.168.1.51", "AA:BB:CC:DD:EE:02", "Device 2", EnforcementBackend.ROOT_IPTABLES)

        assertEquals(2, ruleStateManager.activeRules.value.size)
        assertTrue(ruleStateManager.hasActiveRestrictions())

        ruleStateManager.setBackgroundProtectionEnabled(true)
        assertTrue(ruleStateManager.isBackgroundProtectionEnabled.value)
    }

    @Test
    fun testFirewallChainNameConstant() {
        assertEquals("NETMANAGER_GUARD", FirewallEnforcementEngine.CHAIN_NAME)
    }
}
