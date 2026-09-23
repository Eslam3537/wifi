package com.example.domain.security

import com.example.domain.privilege.CommandExecutor
import com.example.domain.privilege.PrivilegeManager
import com.example.model.AlertSeverity
import com.example.model.ArpAnomalyEvent
import com.example.model.ArpIntegrityReport
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Defensive ARP Security Analyzer.
 * Monitors ARP table changes, detects ARP spoofing symptoms, duplicate MAC anomalies,
 * and tracks IP-to-MAC mapping mutations with timestamps and network interface info.
 */
class ArpSecurityAnalyzer {

    private val _anomalyEvents = MutableStateFlow<List<ArpAnomalyEvent>>(emptyList())
    val anomalyEvents: StateFlow<List<ArpAnomalyEvent>> = _anomalyEvents.asStateFlow()

    // In-memory historical IP -> (MAC, LastSeenTimestamp)
    private val historicalIpToMac = mutableMapOf<String, Pair<String, Long>>()
    private var lastKnownGatewayIp: String? = null
    private var lastKnownGatewayMac: String? = null
    private var cachedEntries: Map<String, String> = emptyMap()
    private var lastFetchTime = 0L

    /**
     * Inspects the neighbor table and compares with historical state to detect anomalies.
     */
    suspend fun auditArpCache(
        gatewayIp: String,
        gatewayMac: String?,
        networkInterface: String = "wlan0"
    ): ArpIntegrityReport = withContext(Dispatchers.IO) {
        val currentEntries = fetchNeighborEntries(networkInterface)
        val detectedAnomalies = mutableListOf<ArpAnomalyEvent>()
        val conflicts = mutableListOf<String>()

        // 1. Gateway MAC Mutation Check (Primary ARP Poisoning signature)
        val resolvedGatewayMac = gatewayMac ?: currentEntries[gatewayIp]
        if (!resolvedGatewayMac.isNullOrBlank()) {
            if (lastKnownGatewayIp == gatewayIp && lastKnownGatewayMac != null && lastKnownGatewayMac != resolvedGatewayMac) {
                val event = ArpAnomalyEvent(
                    ip = gatewayIp,
                    mac = resolvedGatewayMac,
                    previousMac = lastKnownGatewayMac,
                    networkInterface = networkInterface,
                    anomalyType = "GATEWAY_MAC_MUTATION",
                    description = "Default Gateway MAC address changed unexpectedly from $lastKnownGatewayMac to $resolvedGatewayMac (High likelihood of ARP cache poisoning / spoofing)",
                    severity = AlertSeverity.CRITICAL
                )
                detectedAnomalies.add(event)
                conflicts.add("Gateway MAC drift: $gatewayIp ($lastKnownGatewayMac -> $resolvedGatewayMac)")
            }
            lastKnownGatewayIp = gatewayIp
            lastKnownGatewayMac = resolvedGatewayMac
        }

        // 2. Duplicate MAC Detection (Multiple distinct IPs pointing to the same MAC)
        // Group IPs by MAC address, excluding incomplete or broadcast MACs
        val macToIps = currentEntries.filter { (ip, mac) ->
            mac.isNotBlank() && !mac.equals("00:00:00:00:00:00", ignoreCase = true) && !mac.contains("incomplete", ignoreCase = true)
        }.entries.groupBy({ it.value.lowercase() }, { it.key })

        val duplicateMacs = macToIps.filter { it.value.distinct().size > 1 }
        for ((mac, ips) in duplicateMacs) {
            val isGatewayIncluded = ips.contains(gatewayIp)
            val severity = if (isGatewayIncluded) AlertSeverity.CRITICAL else AlertSeverity.WARNING
            val desc = if (isGatewayIncluded) {
                "Critical ARP anomaly: MAC $mac is claiming both Gateway ($gatewayIp) and peer host(s) ${ips.filter { it != gatewayIp }}. Possible Man-In-The-Middle ARP poison."
            } else {
                "Duplicate MAC detected: MAC $mac is shared by multiple IPs: $ips"
            }

            ips.forEach { ip ->
                detectedAnomalies.add(
                    ArpAnomalyEvent(
                        ip = ip,
                        mac = mac,
                        previousMac = null,
                        networkInterface = networkInterface,
                        anomalyType = if (isGatewayIncluded) "GATEWAY_ARP_HIJACK" else "DUPLICATE_MAC",
                        description = desc,
                        severity = severity
                    )
                )
            }
            conflicts.add("Duplicate MAC $mac on IPs: $ips")
        }

        // 3. IP-to-MAC Mutation Check against historical sessions
        val now = System.currentTimeMillis()
        for ((ip, mac) in currentEntries) {
            if (mac.isBlank() || mac.equals("00:00:00:00:00:00", ignoreCase = true)) continue

            val historical = historicalIpToMac[ip]
            if (historical != null && !historical.first.equals(mac, ignoreCase = true)) {
                // IP switched physical hardware
                val event = ArpAnomalyEvent(
                    ip = ip,
                    mac = mac,
                    previousMac = historical.first,
                    networkInterface = networkInterface,
                    anomalyType = "IP_MAC_MAPPING_CHANGED",
                    description = "IP $ip changed hardware address from ${historical.first} to $mac",
                    severity = AlertSeverity.WARNING
                )
                detectedAnomalies.add(event)
                conflicts.add("IP-MAC mismatch for $ip: ${historical.first} vs $mac")
            }
            historicalIpToMac[ip] = mac to now
        }

        if (detectedAnomalies.isNotEmpty()) {
            _anomalyEvents.value = (detectedAnomalies + _anomalyEvents.value)
                .distinctBy { "${it.ip}-${it.mac}-${it.anomalyType}" }
                .take(100)
        }

        val isConsistent = conflicts.isEmpty()
        val statusMsg = if (isConsistent) {
            "ARP cache is healthy and consistent. ${currentEntries.size} verified neighbors."
        } else {
            "Detected ${conflicts.size} ARP anomalies or potential spoofing indicators."
        }

        ArpIntegrityReport(
            checkedAt = now,
            isConsistent = isConsistent,
            gatewayIp = gatewayIp,
            gatewayMac = resolvedGatewayMac,
            entriesCount = currentEntries.size,
            duplicateMacs = duplicateMacs,
            conflictsDetected = conflicts,
            statusMessage = statusMsg
        )
    }

    /**
     * Reads current neighbor cache via system commands safely.
     */
    suspend fun fetchNeighborEntries(networkInterface: String): Map<String, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < 3000L && cachedEntries.isNotEmpty()) {
            return@withContext cachedEntries
        }

        val entries = mutableMapOf<String, String>()
        val isRoot = PrivilegeManager.checkRootStatus() == RootStatus.ROOT_AVAILABLE

        // On Android 10+ (API 29+), untrusted apps are blocked from executing ip neigh or reading netlink neighbor tables.
        // If not root, do not execute the shell command to prevent SELinux audit rate limiting errors.
        if (!isRoot && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return@withContext emptyMap()
        }

        // Method 1: ip neigh show dev <iface>
        val cmd = "ip neigh show dev $networkInterface"
        val cmdResult = if (isRoot) {
            CommandExecutor.executePrivileged(cmd, timeoutSeconds = 3)
        } else {
            CommandExecutor.execute(cmd, timeoutSeconds = 3)
        }

        if (cmdResult.isSuccess && cmdResult.stdout.isNotBlank()) {
            parseIpNeighOutput(cmdResult.stdout, entries)
        }

        // Method 2: ip neigh show (all interfaces fallback if dev was empty)
        if (entries.isEmpty()) {
            val allNeigh = if (isRoot) {
                CommandExecutor.executePrivileged("ip neigh show", timeoutSeconds = 3)
            } else {
                CommandExecutor.execute("ip neigh show", timeoutSeconds = 3)
            }
            if (allNeigh.isSuccess && allNeigh.stdout.isNotBlank()) {
                parseIpNeighOutput(allNeigh.stdout, entries)
            }
        }

        lastFetchTime = now
        cachedEntries = entries
        entries
    }

    private fun parseIpNeighOutput(output: String, targetMap: MutableMap<String, String>) {
        val lines = output.lines()
        for (line in lines) {
            val tokens = line.trim().split("\\s+".toRegex())
            // Expected format: <IP> dev <iface> lladdr <MAC> <STATE>
            if (tokens.size >= 4) {
                val ip = tokens[0]
                val lladdrIndex = tokens.indexOf("lladdr")
                if (lladdrIndex != -1 && lladdrIndex + 1 < tokens.size) {
                    val mac = tokens[lladdrIndex + 1]
                    if (isValidMac(mac)) {
                        targetMap[ip] = mac.uppercase()
                    }
                }
            }
        }
    }

    private fun isValidMac(mac: String): Boolean {
        return mac.matches("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$".toRegex())
    }

    fun clearHistory() {
        historicalIpToMac.clear()
        lastKnownGatewayIp = null
        lastKnownGatewayMac = null
        _anomalyEvents.value = emptyList()
    }
}
