package com.example

import com.example.domain.security.NetworkSecurityMonitor
import com.example.model.AlertSeverity
import com.example.model.DiscoveredDevice
import com.example.model.SecurityAlertType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityMonitorTest {

    @Test
    fun testGatewayDetectionAndChangeAlert() = runBlocking {
        val monitor = NetworkSecurityMonitor()

        // Initial state
        monitor.auditNetworkState(
            currentGatewayIp = "192.168.1.1",
            currentGatewayMac = "74:DA:38:00:00:01",
            currentDnsServers = listOf("8.8.8.8"),
            discoveredDevices = listOf(DiscoveredDevice(ip = "192.168.1.1", isGateway = true))
        )

        // Change gateway IP
        monitor.auditNetworkState(
            currentGatewayIp = "192.168.1.254",
            currentGatewayMac = "74:DA:38:00:00:01",
            currentDnsServers = listOf("8.8.8.8"),
            discoveredDevices = listOf(DiscoveredDevice(ip = "192.168.1.254", isGateway = true))
        )

        val alerts = monitor.alerts.value
        val gwAlert = alerts.find { it.type == SecurityAlertType.GATEWAY_IP_CHANGE }
        assertNotNull(gwAlert)
        assertTrue(gwAlert!!.evidence.contains("192.168.1.1"))
        assertTrue(gwAlert.evidence.contains("192.168.1.254"))
        assertEquals(AlertSeverity.WARNING, gwAlert.severity)
    }

    @Test
    fun testDnsChangeDetection() = runBlocking {
        val monitor = NetworkSecurityMonitor()

        monitor.auditNetworkState(
            currentGatewayIp = "192.168.1.1",
            currentGatewayMac = null,
            currentDnsServers = listOf("1.1.1.1"),
            discoveredDevices = emptyList()
        )

        // DNS hijacked to rogue server
        monitor.auditNetworkState(
            currentGatewayIp = "192.168.1.1",
            currentGatewayMac = null,
            currentDnsServers = listOf("10.0.0.99"),
            discoveredDevices = emptyList()
        )

        val alerts = monitor.alerts.value
        val dnsAlert = alerts.find { it.type == SecurityAlertType.DNS_CHANGE }
        assertNotNull(dnsAlert)
        assertTrue(dnsAlert!!.evidence.contains("10.0.0.99"))
    }

    @Test
    fun testIpConflictDetection() = runBlocking {
        val monitor = NetworkSecurityMonitor()

        val dev1 = DiscoveredDevice(ip = "192.168.1.10", mac = "AA:BB:CC:DD:EE:01")
        val dev2 = DiscoveredDevice(ip = "192.168.1.10", mac = "AA:BB:CC:DD:EE:02")

        monitor.auditNetworkState(
            currentGatewayIp = "192.168.1.1",
            currentGatewayMac = null,
            currentDnsServers = listOf("8.8.8.8"),
            discoveredDevices = listOf(dev1, dev2)
        )

        val alerts = monitor.alerts.value
        val conflictAlert = alerts.find { it.type == SecurityAlertType.IP_CONFLICT }
        assertNotNull(conflictAlert)
        assertEquals(AlertSeverity.CRITICAL, conflictAlert!!.severity)
    }
}
