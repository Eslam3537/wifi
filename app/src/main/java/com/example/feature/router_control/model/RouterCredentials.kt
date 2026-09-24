package com.example.feature.router_control.model

data class RouterCredentials(
    val gatewayIp: String = "192.168.1.1",
    val username: String = "admin",
    val password: String = "",
    val remember: Boolean = true
)
