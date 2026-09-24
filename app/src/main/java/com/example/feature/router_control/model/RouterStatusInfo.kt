package com.example.feature.router_control.model

data class RouterStatusInfo(
    val isConnected: Boolean,
    val vendor: RouterVendor,
    val modelName: String,
    val gatewayIp: String,
    val wifiSsid: String,
    val wifiEnabled: Boolean,
    val connectedDevicesCount: Int,
    val wifiChannel: String = "Auto",
    val uptime: String = "",
    val firmwareVersion: String = "",
    val protocol: String = "http"
)
