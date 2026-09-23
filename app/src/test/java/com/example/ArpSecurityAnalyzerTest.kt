package com.example

import com.example.domain.security.ArpSecurityAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArpSecurityAnalyzerTest {

    @Test
    fun testGatewayMacMutationDetection() = runBlocking {
        val analyzer = ArpSecurityAnalyzer()

        // First audit establishes known gateway MAC
        val report1 = analyzer.auditArpCache(
            gatewayIp = "192.168.1.1",
            gatewayMac = "AA:BB:CC:DD:EE:01",
            networkInterface = "wlan0"
        )
        assertTrue(report1.isConsistent)

        // Gateway MAC drifts to another address (classical ARP poisoning)
        val report2 = analyzer.auditArpCache(
            gatewayIp = "192.168.1.1",
            gatewayMac = "AA:BB:CC:DD:EE:99",
            networkInterface = "wlan0"
        )
        assertFalse(report2.isConsistent)
        assertTrue(report2.conflictsDetected.any { it.contains("Gateway MAC drift") })

        val events = analyzer.anomalyEvents.value
        assertTrue(events.any { it.anomalyType == "GATEWAY_MAC_MUTATION" })
    }

    @Test
    fun testArpAnalyzerHistoryClearing() = runBlocking {
        val analyzer = ArpSecurityAnalyzer()
        analyzer.auditArpCache("192.168.1.1", "AA:BB:CC:DD:EE:01")
        analyzer.clearHistory()
        assertEquals(0, analyzer.anomalyEvents.value.size)
    }
}
