package com.example.feature.router_control.model

data class RouterConnectedDevice(
    val ip: String,
    val mac: String,
    val hostname: String,
    val isBlocked: Boolean = false,
    val connectionType: String = "Wi-Fi",
    val leaseTime: String = "",
    val signalStrength: String = ""
)
