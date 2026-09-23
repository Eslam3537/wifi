package com.example.model

enum class DeviceType {
    PHONE,
    TABLET,
    PC,
    ROUTER,
    SMART_TV,
    PRINTER,
    CAMERA,
    GAMING_CONSOLE,
    IOT,
    UNKNOWN
}

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

enum class IdentitySource {
    DHCP,
    MDNS_NSD,
    SSDP_UPNP,
    NETBIOS,
    REVERSE_DNS,
    HTTP_BANNER,
    MAC_OUI,
    SYSTEM_NEIGHBOR,
    FALLBACK,
    UNKNOWN
}

enum class DeviceStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN
}

enum class DeviceAccessStatus {
    ONLINE,
    BLOCKED,
    ALLOWING,
    ERROR,
    UNKNOWN
}

enum class DeviceSpeedStatus {
    UNLIMITED,
    LIMITED,
    BLOCKED,
    RESTORED,
    FAILED
}

enum class DiscoveryMethod {
    CURRENT_INTERFACE,
    SUBNET_CALCULATION,
    TCP_CONNECT,
    ICMP_PING,
    MDNS_NSD,
    SSDP_UPNP,
    NETBIOS,
    HTTP_PROBE,
    REVERSE_DNS,
    NEIGHBOR_TABLE,
    ROOT_ARP,
    ROUTER_CLIENT_LIST
}

data class DiscoveredDevice(
    val ip: String,
    val mac: String? = null,
    val vendor: String? = null,
    val hostname: String? = null,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val openPorts: List<Int> = emptyList(),
    val discoveryMethods: Set<DiscoveryMethod> = emptySet(),
    val latencyMs: Long? = null,
    val isGateway: Boolean = false,
    val isCurrentDevice: Boolean = false,
    val isProtected: Boolean = false,
    val isMacRandomized: Boolean = false,
    val nameSource: IdentitySource = IdentitySource.UNKNOWN,
    val nameConfidence: ConfidenceLevel = ConfidenceLevel.NONE,
    val vendorSource: IdentitySource = IdentitySource.UNKNOWN,
    val vendorConfidence: ConfidenceLevel = ConfidenceLevel.NONE,
    val mdnsName: String? = null,
    val netbiosName: String? = null,
    val ssdpFriendlyName: String? = null,
    val modelName: String? = null,
    val rawDnsHostname: String? = null,
    val accessStatus: DeviceAccessStatus = DeviceAccessStatus.ONLINE,
    val bandwidthLimitKbps: Long? = null,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val status: DeviceStatus = DeviceStatus.ONLINE
) {
    val displayMac: String
        get() = mac ?: "Unavailable"

    val displayVendor: String
        get() = if (vendor.isNullOrBlank()) "Unavailable" else vendor

    val displayHostname: String
        get() = if (hostname.isNullOrBlank()) "Unavailable" else hostname

    val speedStatus: DeviceSpeedStatus
        get() = when {
            accessStatus == DeviceAccessStatus.BLOCKED -> DeviceSpeedStatus.BLOCKED
            bandwidthLimitKbps != null && bandwidthLimitKbps > 0 -> DeviceSpeedStatus.LIMITED
            else -> DeviceSpeedStatus.UNLIMITED
        }

    val formattedBandwidthLimit: String
        get() = when {
            bandwidthLimitKbps == null || bandwidthLimitKbps <= 0 -> "Unlimited"
            bandwidthLimitKbps >= 1000 -> "${bandwidthLimitKbps / 1000} Mbps"
            else -> "$bandwidthLimitKbps Kbps"
        }
}

enum class DiagnosticStatus {
    MEASURED,
    UNAVAILABLE,
    BLOCKED,
    TIMED_OUT
}

data class NetworkDiagnostics(
    val targetIp: String,
    val pingMs: Double? = null,
    val minLatencyMs: Double? = null,
    val maxLatencyMs: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPercent: Double? = null,
    val dnsLatencyMs: Long? = null,
    val downloadSpeedMbps: Double? = null,
    val uploadSpeedMbps: Double? = null,
    val status: DiagnosticStatus = DiagnosticStatus.MEASURED,
    val statusMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class AlertConfidence {
    LOW,
    MEDIUM,
    HIGH
}

enum class SecurityAlertType {
    GATEWAY_IP_CHANGE,
    GATEWAY_IDENTITY_CHANGE,
    DNS_CHANGE,
    NEW_DEVICE,
    IP_CONFLICT,
    ARP_SPOOFING_SUSPECTED,
    ARP_DUPLICATE_MAC,
    ARP_GATEWAY_MUTATION,
    ARP_CACHE_POISONED
}

data class ArpAnomalyEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val ip: String,
    val mac: String,
    val previousMac: String? = null,
    val networkInterface: String = "wlan0",
    val anomalyType: String,
    val description: String,
    val severity: AlertSeverity = AlertSeverity.CRITICAL
)

data class ArpIntegrityReport(
    val checkedAt: Long = System.currentTimeMillis(),
    val isConsistent: Boolean,
    val gatewayIp: String,
    val gatewayMac: String?,
    val entriesCount: Int,
    val duplicateMacs: Map<String, List<String>> = emptyMap(),
    val conflictsDetected: List<String> = emptyList(),
    val statusMessage: String
)

data class SecurityAlert(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: SecurityAlertType,
    val title: String,
    val evidence: String,
    val confidence: AlertConfidence,
    val recommendedAction: String,
    val severity: AlertSeverity
)

data class RouterCapability(
    val detectedModel: String = "Unknown / Unresponsive",
    val endpoint: String = "",
    val isSupported: Boolean = false,
    val supportedFeatures: List<String> = emptyList(),
    val unsupportedReasons: List<String> = emptyList(),
    val protocol: String = "UNKNOWN"
)

enum class RootStatus {
    NO_ROOT,
    ROOT_AVAILABLE,
    ROOT_DENIED,
    ROOT_UNSUPPORTED
}

enum class KernelSuStatus {
    NOT_DETECTED,
    DETECTED_NO_PERMISSION,
    SUPPORTED_AND_VERIFIED
}

data class CompatibilityAuditResult(
    val targetDevice: String = "POCO X7 Pro (Dimensity 8400 Ultra)",
    val osVersion: String = "Android 16 (HyperOS)",
    val apiLevel: Int,
    val isArm64: Boolean,
    val macAddressRestricted: Boolean,
    val procNetArpRestricted: Boolean,
    val localNetworkMulticastSupported: Boolean,
    val backgroundExecutionRestricted: Boolean,
    val notificationsGranted: Boolean,
    val wifiStateGranted: Boolean,
    val fineLocationGranted: Boolean,
    val rootStatus: RootStatus,
    val kernelSuStatus: KernelSuStatus
)

enum class CapabilityState {
    SUPPORTED,
    LIMITED,
    RESTRICTED,
    UNSUPPORTED
}

data class SystemCapabilityReport(
    val root: CapabilityState,
    val kernelSu: CapabilityState,
    val networkScan: CapabilityState,
    val mDns: CapabilityState,
    val tcpScan: CapabilityState,
    val icmp: CapabilityState,
    val macAccess: CapabilityState,
    val arpInfo: CapabilityState,
    val routerControl: CapabilityState,
    val blockDevice: CapabilityState,
    val blockAll: CapabilityState
)
