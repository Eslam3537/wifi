package com.example.domain.router.model

enum class RouterManufacturer {
    HUAWEI,
    TPLINK,
    ZTE,
    DLINK,
    GENERIC,
    UNKNOWN
}

data class DetectedRouter(
    val gatewayIp: String,
    val manufacturer: RouterManufacturer = RouterManufacturer.UNKNOWN,
    val modelName: String = "Unknown Gateway",
    val firmwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val webAdminTitle: String? = null,
    val isSupported: Boolean = false,
    val detectedFeatures: List<String> = emptyList(),
    val rawSignatures: List<String> = emptyList()
)

sealed class RouterDiscoveryState {
    object Idle : RouterDiscoveryState()
    object NotOnWifi : RouterDiscoveryState()
    data class Detecting(val candidateIp: String?) : RouterDiscoveryState()
    data class Detected(val router: DetectedRouter) : RouterDiscoveryState()
    data class NotDetected(val candidateIp: String?, val reason: String) : RouterDiscoveryState()
}
