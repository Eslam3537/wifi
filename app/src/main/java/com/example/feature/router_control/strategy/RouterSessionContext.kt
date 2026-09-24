package com.example.feature.router_control.strategy

import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterVendor

data class RouterSessionContext(
    val gatewayIp: String,
    val credentials: RouterCredentials,
    val vendor: RouterVendor,
    val protocol: String = credentials.protocol,
    val cookies: MutableMap<String, String> = mutableMapOf(),
    var authToken: String? = null,
    var csrfToken: String? = null,
    val customHeaders: MutableMap<String, String> = mutableMapOf()
) {
    fun buildCookieHeader(): String {
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getCleanGatewayIp(): String {
        return gatewayIp.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
    }

    fun getBaseUrl(): String {
        val clean = getCleanGatewayIp()
        val proto = protocol.ifBlank { credentials.protocol }.ifBlank { "http" }
        return "$proto://$clean"
    }

    fun buildUrl(path: String): String {
        val cleanPath = path.removePrefix("/")
        return "${getBaseUrl()}/$cleanPath"
    }
}
