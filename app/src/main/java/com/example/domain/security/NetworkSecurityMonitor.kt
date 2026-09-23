package com.example.domain.security

import com.example.model.AlertConfidence
import com.example.model.AlertSeverity
import com.example.model.DiscoveredDevice
import com.example.model.SecurityAlert
import com.example.model.SecurityAlertType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkSecurityMonitor {

    val arpAnalyzer = ArpSecurityAnalyzer()

    private val _alerts = MutableStateFlow<List<SecurityAlert>>(emptyList())
    val alerts: StateFlow<List<SecurityAlert>> = _alerts.asStateFlow()

    private var previousGatewayIp: String? = null
    private var previousGatewayMac: String? = null
    private var previousDnsServers: List<String>? = null
    private val knownDeviceIps = mutableSetOf<String>()

    suspend fun auditNetworkState(
        currentGatewayIp: String,
        currentGatewayMac: String?,
        currentDnsServers: List<String>,
        discoveredDevices: List<DiscoveredDevice>,
        networkInterface: String = "wlan0"
    ) {
        val newAlerts = mutableListOf<SecurityAlert>()

        // 1. Check Gateway IP Change
        if (previousGatewayIp != null && previousGatewayIp != currentGatewayIp) {
            newAlerts.add(
                SecurityAlert(
                    type = SecurityAlertType.GATEWAY_IP_CHANGE,
                    title = "Default Gateway IP Changed",
                    evidence = "Previous Gateway: $previousGatewayIp -> New Gateway: $currentGatewayIp",
                    confidence = AlertConfidence.HIGH,
                    recommendedAction = "Verify if you switched Wi-Fi networks or if an unauthorized DHCP server is active on the LAN.",
                    severity = AlertSeverity.WARNING
                )
            )
        }
        previousGatewayIp = currentGatewayIp

        // 2. Check Gateway Identity / MAC change (when observable)
        if (currentGatewayMac != null && previousGatewayMac != null && previousGatewayMac != currentGatewayMac) {
            newAlerts.add(
                SecurityAlert(
                    type = SecurityAlertType.GATEWAY_IDENTITY_CHANGE,
                    title = "Gateway Hardware Address Changed",
                    evidence = "Gateway $currentGatewayIp MAC changed from $previousGatewayMac to $currentGatewayMac",
                    confidence = AlertConfidence.HIGH,
                    recommendedAction = "Possible ARP spoofing or gateway replacement detected. Inspect router admin panel immediately.",
                    severity = AlertSeverity.CRITICAL
                )
            )
        }
        if (currentGatewayMac != null) {
            previousGatewayMac = currentGatewayMac
        }

        // 3. Check DNS Change
        if (previousDnsServers != null && previousDnsServers != currentDnsServers && currentDnsServers.isNotEmpty()) {
            newAlerts.add(
                SecurityAlert(
                    type = SecurityAlertType.DNS_CHANGE,
                    title = "DNS Server Configuration Changed",
                    evidence = "Previous: $previousDnsServers -> Current: $currentDnsServers",
                    confidence = AlertConfidence.MEDIUM,
                    recommendedAction = "Verify if DNS hijacking, captive portal, or VPN redirection is active.",
                    severity = AlertSeverity.WARNING
                )
            )
        }
        if (currentDnsServers.isNotEmpty()) {
            previousDnsServers = currentDnsServers
        }

        // 4. Run deep ARP Cache and Spoofing Analyzer
        val arpReport = arpAnalyzer.auditArpCache(currentGatewayIp, currentGatewayMac, networkInterface)
        if (!arpReport.isConsistent) {
            for (conflict in arpReport.conflictsDetected) {
                val isGatewayDrift = conflict.contains("Gateway", ignoreCase = true)
                val alertType = if (isGatewayDrift) SecurityAlertType.ARP_GATEWAY_MUTATION else SecurityAlertType.ARP_DUPLICATE_MAC
                newAlerts.add(
                    SecurityAlert(
                        type = alertType,
                        title = if (isGatewayDrift) "Gateway ARP Poisoning Detected" else "Duplicate MAC / ARP Anomaly",
                        evidence = conflict,
                        confidence = AlertConfidence.HIGH,
                        recommendedAction = "Investigate unauthorized hosts on network. Consider enabling Dynamic ARP Inspection or Static ARP on router.",
                        severity = if (isGatewayDrift) AlertSeverity.CRITICAL else AlertSeverity.WARNING
                    )
                )
            }
        }

        // 5. Check for New Devices & IP Conflicts
        val ipToDevices = discoveredDevices.groupBy { it.ip }
        for ((ip, devices) in ipToDevices) {
            if (devices.size > 1) {
                newAlerts.add(
                    SecurityAlert(
                        type = SecurityAlertType.IP_CONFLICT,
                        title = "IP Address Conflict Detected",
                        evidence = "Multiple responses detected for IP $ip ($devices)",
                        confidence = AlertConfidence.HIGH,
                        recommendedAction = "Two devices on the local subnet are using the same static IP address.",
                        severity = AlertSeverity.CRITICAL
                    )
                )
            }

            if (!knownDeviceIps.contains(ip)) {
                knownDeviceIps.add(ip)
                if (knownDeviceIps.size > 2) { // Skip self and gateway initial notice
                    newAlerts.add(
                        SecurityAlert(
                            type = SecurityAlertType.NEW_DEVICE,
                            title = "New Device Discovered",
                            evidence = "New IP detected on LAN: $ip (${devices.firstOrNull()?.vendor ?: "Unknown"})",
                            confidence = AlertConfidence.MEDIUM,
                            recommendedAction = "Review device details to ensure it is an authorized host on your network.",
                            severity = AlertSeverity.INFO
                        )
                    )
                }
            }
        }

        if (newAlerts.isNotEmpty()) {
            _alerts.value = (newAlerts + _alerts.value).distinctBy { it.evidence }.take(50)
        }
    }

    fun clearAlerts() {
        _alerts.value = emptyList()
        arpAnalyzer.clearHistory()
    }
}
