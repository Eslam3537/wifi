package com.example.domain.router.model

enum class RouterConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    AUTH_FAILED,
    NETWORK_ERROR,
    SESSION_EXPIRED
}

data class RouterCredentials(
    val username: String,
    val password: String,
    val gatewayIp: String
)

data class RouterSession(
    val gatewayIp: String,
    val sessionId: String? = null,
    val csrfToken: String? = null,
    val cookies: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + (15 * 60 * 1000) // 15 min default
) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAt
}

data class RouterDeviceInfo(
    val model: String = "Not available",
    val firmwareVersion: String = "Not available",
    val hardwareVersion: String = "Not available",
    val serialNumber: String = "Not available",
    val uptimeSeconds: Long = 0L,
    val formattedUptime: String = "Not available",
    val deviceName: String = "Not available"
)

data class RouterWanInfo(
    val status: String = "Not available",
    val connectionType: String = "Not available",
    val ip: String = "Not available",
    val subnet: String = "Not available",
    val gateway: String = "Not available",
    val primaryDns: String = "Not available",
    val secondaryDns: String = "Not available",
    val mac: String = "Not available"
)

data class RouterLanInfo(
    val ip: String = "Not available",
    val subnet: String = "Not available",
    val dhcpEnabled: Boolean = false,
    val dhcpStart: String = "Not available",
    val dhcpEnd: String = "Not available"
)

data class RouterWifiInfo(
    val enabled: Boolean = false,
    val ssid: String = "Not available",
    val channel: String = "Auto",
    val securityMode: String = "Not available",
    val standard: String = "802.11b/g/n",
    val transmitPower: String = "100%"
)

data class RouterConnectedDevice(
    val name: String = "Unknown Device",
    val ip: String = "Not available",
    val mac: String = "Not available",
    val connectionType: String = "Wi-Fi (2.4 GHz)", // Wi-Fi / Ethernet
    val isOnline: Boolean = true,
    val leaseTime: String = "Not available"
)

data class RouterDashboardData(
    val deviceInfo: RouterDeviceInfo = RouterDeviceInfo(),
    val wanInfo: RouterWanInfo = RouterWanInfo(),
    val lanInfo: RouterLanInfo = RouterLanInfo(),
    val wifiInfo: RouterWifiInfo = RouterWifiInfo(),
    val connectedDevices: List<RouterConnectedDevice> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class WifiUpdatePayload(
    val ssid: String,
    val password: String? = null,
    val enabled: Boolean? = null,
    val channel: String? = null
)
