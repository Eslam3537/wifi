package com.example.feature.router_control.model

enum class RouterVendor(val displayName: String, val defaultPort: Int = 80) {
    TP_LINK("TP-Link", 80),
    HUAWEI("Huawei Home Gateway", 80),
    ZTE("ZTE Corporation", 80),
    MIKROTIK("MikroTik RouterOS", 80),
    GENERIC("Generic Gateway", 80),
    UNSUPPORTED("Unsupported Router", 80)
}
