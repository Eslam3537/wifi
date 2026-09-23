package com.example.domain.discovery

import com.example.model.DeviceStatus
import com.example.model.DiscoveryMethod

enum class EvidenceStrength {
    STRONG,   // Verified TCP connection, current mDNS/SSDP response, verified ICMP ping, active router client list
    MEDIUM,   // Reachable/Delay neighbor entry with recent confirmation, NetBIOS response
    WEAK      // Stale neighbor, reverse DNS only, cached database, failed neighbor
}

data class DeviceEvidence(
    val ip: String,
    val mac: String? = null,
    val hostname: String? = null,
    val source: DiscoveryMethod,
    val timestamp: Long = System.currentTimeMillis(),
    val strength: EvidenceStrength,
    val responseStatus: String,
    val details: String = ""
)

data class PresenceDecision(
    val status: DeviceStatus,
    val primaryReason: String,
    val evidenceList: List<DeviceEvidence>,
    val verifiedOnlineTimestamp: Long?
)

/**
 * Dedicated presence validator separating live network evidence from historical or stale data.
 * Weak evidence (such as STALE neighbor or cached DB entry) CANNOT independently mark a device ONLINE.
 */
object DevicePresenceEngine {

    fun evaluatePresence(ip: String, evidenceList: List<DeviceEvidence>): PresenceDecision {
        val now = System.currentTimeMillis()
        // Consider only recent evidence within 120 seconds
        val recentEvidences = evidenceList.filter { (now - it.timestamp) < 120_000L }

        val strong = recentEvidences.filter { it.strength == EvidenceStrength.STRONG }
        val medium = recentEvidences.filter { it.strength == EvidenceStrength.MEDIUM }
        val weak = recentEvidences.filter { it.strength == EvidenceStrength.WEAK }

        return when {
            strong.isNotEmpty() -> {
                val primary = strong.first()
                PresenceDecision(
                    status = DeviceStatus.ONLINE,
                    primaryReason = "Verified online via ${primary.source} (${primary.details})",
                    evidenceList = recentEvidences,
                    verifiedOnlineTimestamp = primary.timestamp
                )
            }
            medium.isNotEmpty() -> {
                val primary = medium.first()
                PresenceDecision(
                    status = DeviceStatus.ONLINE,
                    primaryReason = "Verified active via ${primary.source} (${primary.details})",
                    evidenceList = recentEvidences,
                    verifiedOnlineTimestamp = primary.timestamp
                )
            }
            else -> {
                val reason = if (weak.isNotEmpty()) {
                    "Only weak or historical evidence present (${weak.joinToString { "${it.source}: ${it.responseStatus}" }})"
                } else {
                    "No response to active network probes"
                }
                PresenceDecision(
                    status = DeviceStatus.OFFLINE,
                    primaryReason = reason,
                    evidenceList = recentEvidences,
                    verifiedOnlineTimestamp = null
                )
            }
        }
    }
}
