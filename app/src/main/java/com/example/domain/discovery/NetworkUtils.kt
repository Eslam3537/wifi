package com.example.domain.discovery

import com.example.model.ConfidenceLevel
import com.example.model.DeviceAccessStatus
import com.example.model.DeviceStatus
import com.example.model.DiscoveredDevice
import java.net.InetAddress

object NetworkUtils {

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255 && (part == "0" || !part.startsWith("0"))
        }
    }

    fun ipToInt(ip: String): Int {
        val parts = ip.split(".").map { it.toInt() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    fun intToIp(value: Int): String {
        return "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }

    fun calculateSubnetRange(ip: String, prefixLength: Int): List<String> {
        if (!isValidIpv4(ip) || prefixLength !in 8..30) {
            return emptyList()
        }

        val ipInt = ipToInt(ip)
        val mask = (-1 shl (32 - prefixLength))
        val network = ipInt and mask
        val broadcast = network or mask.inv()

        val start = network + 1
        val end = broadcast - 1

        if (start > end) return emptyList()

        // Limit range for safety and memory (max 254 addresses for typical /24)
        val count = (end - start + 1).coerceAtMost(254)
        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            result.add(intToIp(start + i))
        }
        return result
    }

    fun calculateJitter(latencies: List<Double>): Double {
        if (latencies.size < 2) return 0.0
        var totalDiff = 0.0
        for (i in 0 until latencies.size - 1) {
            totalDiff += kotlin.math.abs(latencies[i + 1] - latencies[i])
        }
        val jitter = totalDiff / (latencies.size - 1)
        return (kotlin.math.round(jitter * 100.0)) / 100.0
    }

    fun calculatePacketLoss(sent: Int, received: Int): Double {
        if (sent <= 0) return 0.0
        val lost = (sent - received).coerceAtLeast(0)
        val percent = (lost.toDouble() / sent.toDouble()) * 100.0
        return (kotlin.math.round(percent * 10.0)) / 10.0
    }

    /**
     * Merges two active discovery scan batches.
     * All devices in this merge are from the active scan and have status ONLINE.
     */
    fun deduplicateDevices(
        currentList: List<DiscoveredDevice>,
        newList: List<DiscoveredDevice>
    ): List<DiscoveredDevice> {
        val map = currentList.associateBy { it.ip }.toMutableMap()

        for (newDev in newList) {
            val existing = map[newDev.ip]
            if (existing != null) {
                val mergedMethods = existing.discoveryMethods + newDev.discoveryMethods
                val mergedPorts = (existing.openPorts + newDev.openPorts).distinct().sorted()

                val mac = if (!newDev.mac.isNullOrBlank() && newDev.mac != "Unavailable") newDev.mac else existing.mac

                val vendor = when {
                    !newDev.vendor.isNullOrBlank() && newDev.vendor != "Unknown manufacturer" && newDev.vendor != "Unavailable" -> newDev.vendor
                    !existing.vendor.isNullOrBlank() && existing.vendor != "Unknown manufacturer" && existing.vendor != "Unavailable" -> existing.vendor
                    else -> newDev.vendor ?: existing.vendor
                }

                val hostname = when {
                    !newDev.hostname.isNullOrBlank() && DeviceFingerprintHelper.isReliableHostname(newDev.hostname) -> newDev.hostname
                    !existing.hostname.isNullOrBlank() && DeviceFingerprintHelper.isReliableHostname(existing.hostname) -> existing.hostname
                    else -> newDev.hostname ?: existing.hostname
                }

                val mdnsName = newDev.mdnsName ?: existing.mdnsName
                val netbiosName = newDev.netbiosName ?: existing.netbiosName
                val ssdpFriendlyName = newDev.ssdpFriendlyName ?: existing.ssdpFriendlyName
                val modelName = newDev.modelName ?: existing.modelName
                val rawDnsHostname = newDev.rawDnsHostname ?: existing.rawDnsHostname

                val deviceType = if (newDev.deviceType != com.example.model.DeviceType.UNKNOWN) newDev.deviceType else existing.deviceType
                val nameConfidence = if (newDev.nameConfidence.ordinal < existing.nameConfidence.ordinal) newDev.nameConfidence else existing.nameConfidence
                val vendorConfidence = if (newDev.vendorConfidence.ordinal < existing.vendorConfidence.ordinal) newDev.vendorConfidence else existing.vendorConfidence
                val nameSource = if (newDev.nameSource != com.example.model.IdentitySource.UNKNOWN) newDev.nameSource else existing.nameSource
                val vendorSource = if (newDev.vendorSource != com.example.model.IdentitySource.UNKNOWN) newDev.vendorSource else existing.vendorSource

                map[newDev.ip] = existing.copy(
                    mac = mac,
                    vendor = vendor,
                    hostname = hostname,
                    deviceType = deviceType,
                    openPorts = mergedPorts,
                    discoveryMethods = mergedMethods,
                    latencyMs = newDev.latencyMs ?: existing.latencyMs,
                    isProtected = existing.isProtected || newDev.isProtected,
                    isMacRandomized = existing.isMacRandomized || newDev.isMacRandomized,
                    nameSource = nameSource,
                    nameConfidence = nameConfidence,
                    vendorSource = vendorSource,
                    vendorConfidence = vendorConfidence,
                    mdnsName = mdnsName,
                    netbiosName = netbiosName,
                    ssdpFriendlyName = ssdpFriendlyName,
                    modelName = modelName,
                    rawDnsHostname = rawDnsHostname,
                    accessStatus = if (existing.accessStatus != DeviceAccessStatus.ONLINE) existing.accessStatus else newDev.accessStatus,
                    lastSeen = System.currentTimeMillis(),
                    status = DeviceStatus.ONLINE
                )
            } else {
                map[newDev.ip] = newDev
            }
        }

        return map.values.sortedWith(
            compareByDescending<DiscoveredDevice> { it.isGateway }
                .thenByDescending { it.isCurrentDevice }
                .thenBy { ipToIntOrZero(it.ip) }
        )
    }

    /**
     * Reconciles current verified scan with database history.
     * INVARIANT:
     * - Verified scan devices remain ONLINE.
     * - User annotations (isProtected, custom name, etc.) are restored from DB.
     * - DB devices absent from the current scan are marked OFFLINE.
     */
    fun reconcileScanWithDatabaseHistory(
        verifiedScan: List<DiscoveredDevice>,
        dbHistory: List<DiscoveredDevice>
    ): List<DiscoveredDevice> {
        val scanIps = verifiedScan.map { it.ip }.toSet()
        val dbByIp = dbHistory.associateBy { it.ip }

        val reconciledScan = verifiedScan.map { dev ->
            val historical = dbByIp[dev.ip]
            if (historical != null) {
                dev.copy(
                    firstSeen = historical.firstSeen,
                    isProtected = historical.isProtected,
                    bandwidthLimitKbps = historical.bandwidthLimitKbps,
                    accessStatus = if (historical.accessStatus != DeviceAccessStatus.ONLINE) historical.accessStatus else dev.accessStatus,
                    hostname = if (!historical.hostname.isNullOrBlank() && (dev.hostname.isNullOrBlank() || dev.nameConfidence == ConfidenceLevel.LOW)) historical.hostname else dev.hostname,
                    status = DeviceStatus.ONLINE
                )
            } else {
                dev.copy(status = DeviceStatus.ONLINE)
            }
        }

        val offlineHistorical = dbHistory
            .filter { it.ip !in scanIps }
            .map { it.copy(status = DeviceStatus.OFFLINE) }

        return (reconciledScan + offlineHistorical).sortedWith(
            compareByDescending<DiscoveredDevice> { it.status == DeviceStatus.ONLINE }
                .thenByDescending { it.isGateway }
                .thenByDescending { it.isCurrentDevice }
                .thenBy { ipToIntOrZero(it.ip) }
        )
    }

    private fun ipToIntOrZero(ip: String): Int {
        return try {
            ipToInt(ip)
        } catch (_: Exception) {
            0
        }
    }
}
