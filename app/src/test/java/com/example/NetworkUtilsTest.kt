package com.example

import com.example.domain.discovery.NetworkUtils
import com.example.model.DeviceStatus
import com.example.model.DeviceType
import com.example.model.DiscoveredDevice
import com.example.model.DiscoveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testIpValidation_validAndInvalid() {
        assertTrue(NetworkUtils.isValidIpv4("192.168.1.1"))
        assertTrue(NetworkUtils.isValidIpv4("10.0.0.1"))
        assertTrue(NetworkUtils.isValidIpv4("172.16.0.254"))
        assertTrue(NetworkUtils.isValidIpv4("0.0.0.0"))
        assertTrue(NetworkUtils.isValidIpv4("255.255.255.255"))

        assertFalse(NetworkUtils.isValidIpv4("192.168.1.256"))
        assertFalse(NetworkUtils.isValidIpv4("192.168.1"))
        assertFalse(NetworkUtils.isValidIpv4("192.168.1.1.1"))
        assertFalse(NetworkUtils.isValidIpv4("abc.def.ghi.jkl"))
        assertFalse(NetworkUtils.isValidIpv4("192.168.01.1")) // leading zero
        assertFalse(NetworkUtils.isValidIpv4(""))
    }

    @Test
    fun testSubnetCalculation_standardSlash24() {
        val range = NetworkUtils.calculateSubnetRange("192.168.1.100", 24)
        assertEquals(254, range.size)
        assertEquals("192.168.1.1", range.first())
        assertEquals("192.168.1.254", range.last())
        assertTrue(range.contains("192.168.1.100"))
    }

    @Test
    fun testSubnetCalculation_invalidInputs() {
        val emptyOnInvalidIp = NetworkUtils.calculateSubnetRange("not_an_ip", 24)
        assertTrue(emptyOnInvalidIp.isEmpty())

        val emptyOnInvalidPrefix = NetworkUtils.calculateSubnetRange("192.168.1.1", 32)
        assertTrue(emptyOnInvalidPrefix.isEmpty())
    }

    @Test
    fun testJitterCalculation() {
        val latencies = listOf(10.0, 15.0, 12.0, 18.0)
        // Diffs: |15-10|=5, |12-15|=3, |18-12|=6. Sum = 14. Avg = 14 / 3 = 4.67
        val jitter = NetworkUtils.calculateJitter(latencies)
        assertEquals(4.67, jitter, 0.05)

        assertEquals(0.0, NetworkUtils.calculateJitter(listOf(10.0)), 0.001)
        assertEquals(0.0, NetworkUtils.calculateJitter(emptyList()), 0.001)
    }

    @Test
    fun testPacketLossCalculation() {
        assertEquals(0.0, NetworkUtils.calculatePacketLoss(5, 5), 0.01)
        assertEquals(20.0, NetworkUtils.calculatePacketLoss(5, 4), 0.01)
        assertEquals(40.0, NetworkUtils.calculatePacketLoss(5, 3), 0.01)
        assertEquals(100.0, NetworkUtils.calculatePacketLoss(5, 0), 0.01)
        assertEquals(0.0, NetworkUtils.calculatePacketLoss(0, 0), 0.01)
    }

    @Test
    fun testDeviceDeduplication_mergesDataCorrectly() {
        val dev1 = DiscoveredDevice(
            ip = "192.168.1.50",
            mac = null,
            vendor = "Unknown manufacturer",
            openPorts = listOf(80),
            discoveryMethods = setOf(DiscoveryMethod.TCP_CONNECT),
            latencyMs = 12L
        )

        val dev2 = DiscoveredDevice(
            ip = "192.168.1.50",
            mac = "F8:E0:79:AA:BB:CC",
            vendor = "Xiaomi Communications Co Ltd",
            hostname = "my-phone",
            openPorts = listOf(80, 8080),
            discoveryMethods = setOf(DiscoveryMethod.MDNS_NSD),
            latencyMs = 10L
        )

        val deduplicated = NetworkUtils.deduplicateDevices(listOf(dev1), listOf(dev2))
        assertEquals(1, deduplicated.size)

        val merged = deduplicated.first()
        assertEquals("192.168.1.50", merged.ip)
        assertEquals("F8:E0:79:AA:BB:CC", merged.mac)
        assertEquals("Xiaomi Communications Co Ltd", merged.vendor)
        assertEquals("my-phone", merged.hostname)
        assertEquals(listOf(80, 8080), merged.openPorts)
        assertTrue(merged.discoveryMethods.contains(DiscoveryMethod.TCP_CONNECT))
        assertTrue(merged.discoveryMethods.contains(DiscoveryMethod.MDNS_NSD))
    }

    @Test
    fun testDeviceStatus_sortingPriority() {
        val gw = DiscoveredDevice(ip = "192.168.1.1", isGateway = true)
        val self = DiscoveredDevice(ip = "192.168.1.5", isCurrentDevice = true)
        val other = DiscoveredDevice(ip = "192.168.1.2")

        val sorted = NetworkUtils.deduplicateDevices(emptyList(), listOf(other, self, gw))
        assertEquals("192.168.1.1", sorted[0].ip)
        assertEquals("192.168.1.5", sorted[1].ip)
        assertEquals("192.168.1.2", sorted[2].ip)
    }

    @Test
    fun testReconcileScanWithDatabaseHistory_marksAbsentDevicesOffline() {
        val now = System.currentTimeMillis()
        val onlineDev = DiscoveredDevice(
            ip = "192.168.1.10",
            mac = "AA:BB:CC:DD:EE:01",
            status = DeviceStatus.ONLINE,
            lastSeen = now
        )

        val staleHistoricalDev = DiscoveredDevice(
            ip = "192.168.1.20",
            mac = "AA:BB:CC:DD:EE:02",
            status = DeviceStatus.ONLINE, // Previously saved as online
            lastSeen = now - 600_000,
            hostname = "old-laptop"
        )

        val reconciled = NetworkUtils.reconcileScanWithDatabaseHistory(
            verifiedScan = listOf(onlineDev),
            dbHistory = listOf(staleHistoricalDev)
        )

        assertEquals(2, reconciled.size)

        val onlineResult = reconciled.find { it.ip == "192.168.1.10" }!!
        assertEquals(DeviceStatus.ONLINE, onlineResult.status)

        val offlineResult = reconciled.find { it.ip == "192.168.1.20" }!!
        assertEquals(DeviceStatus.OFFLINE, offlineResult.status)
        assertEquals("old-laptop", offlineResult.hostname) // Metadata preserved
    }

    @Test
    fun testTrafficPathAnalyzer_stationModeDoesNotTraverse() = kotlinx.coroutines.runBlocking {
        val path = com.example.domain.protection.TrafficPathAnalyzer.analyzeTrafficPath(
            localIp = "192.168.1.100",
            gatewayIp = "192.168.1.1",
            interfaceName = "wlan0"
        )

        assertEquals(com.example.domain.protection.PhoneNetworkRole.NORMAL_WIFI_CLIENT, path.role)
        assertFalse(path.isForwardingEnabled)
        assertFalse(path.canLocalFirewallBlockRemoteClients)
    }

    @Test
    fun testTrafficPathAnalyzer_gatewayRoleTraverses() = kotlinx.coroutines.runBlocking {
        val path = com.example.domain.protection.TrafficPathAnalyzer.analyzeTrafficPath(
            localIp = "192.168.43.1",
            gatewayIp = "192.168.43.1",
            interfaceName = "wlan1"
        )

        assertEquals(com.example.domain.protection.PhoneNetworkRole.ROUTER_GATEWAY, path.role)
        assertTrue(path.isForwardingEnabled)
        assertTrue(path.canLocalFirewallBlockRemoteClients)
    }

    @Test
    fun testDevicePresenceEngine_evaluatesPresenceConfidence() {
        val strongEvidence = listOf(
            com.example.domain.discovery.DeviceEvidence(
                ip = "192.168.1.50",
                source = DiscoveryMethod.TCP_CONNECT,
                strength = com.example.domain.discovery.EvidenceStrength.STRONG,
                responseStatus = "SYN-ACK",
                details = "Port 80 reachable"
            )
        )
        val decisionStrong = com.example.domain.discovery.DevicePresenceEngine.evaluatePresence("192.168.1.50", strongEvidence)
        assertEquals(DeviceStatus.ONLINE, decisionStrong.status)

        val weakEvidence = listOf(
            com.example.domain.discovery.DeviceEvidence(
                ip = "192.168.1.51",
                source = DiscoveryMethod.NEIGHBOR_TABLE,
                strength = com.example.domain.discovery.EvidenceStrength.WEAK,
                responseStatus = "STALE",
                details = "Cached neighbor entry without active response"
            )
        )
        val decisionWeak = com.example.domain.discovery.DevicePresenceEngine.evaluatePresence("192.168.1.51", weakEvidence)
        assertEquals(DeviceStatus.OFFLINE, decisionWeak.status)
    }
}
