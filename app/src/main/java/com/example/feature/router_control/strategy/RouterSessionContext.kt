package com.example.feature.router_control.strategy

import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterVendor

data class RouterSessionContext(
    val gatewayIp: String,
    val credentials: RouterCredentials,
    val vendor: RouterVendor,
    val cookies: MutableMap<String, String> = mutableMapOf(),
    var authToken: String? = null,
    var csrfToken: String? = null,
    val customHeaders: MutableMap<String, String> = mutableMapOf()
) {
    fun buildCookieHeader(): String {
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
