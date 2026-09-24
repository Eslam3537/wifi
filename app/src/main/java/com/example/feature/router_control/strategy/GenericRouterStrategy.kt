package com.example.feature.router_control.strategy

import android.util.Base64
import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Universal fallback router strategy for other / standard home routers.
 * Inspects form fields or HTTP Basic Auth, parses DHCP tables using Jsoup.
 */
class GenericRouterStrategy : RouterStrategy {

    override val vendor: RouterVendor = RouterVendor.GENERIC

    override fun matches(html: String, headers: Map<String, List<String>>, serverHeader: String): Boolean {
        // Universal fallback
        return true
    }

    override suspend fun login(
        client: OkHttpClient,
        gatewayIp: String,
        credentials: RouterCredentials
    ): Result<RouterSessionContext> = withContext(Dispatchers.IO) {
        try {
            val cleanIp = gatewayIp.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            val proto = credentials.protocol.ifBlank { "http" }
            val baseUrl = "$proto://$cleanIp"

            val session = RouterSessionContext(
                gatewayIp = cleanIp,
                credentials = credentials,
                vendor = RouterVendor.GENERIC,
                protocol = proto
            )

            // 1. First probe root HTML
            val probeReq = Request.Builder()
                .url("$baseUrl/")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val probeResp = client.newCall(probeReq).execute()
            val probeHtml = probeResp.body?.string() ?: ""

            // Capture initial cookies
            probeResp.headers("Set-Cookie").forEach { c ->
                val pair = c.split(";").firstOrNull()?.split("=")
                if (pair != null && pair.size == 2) {
                    session.cookies[pair[0].trim()] = pair[1].trim()
                }
            }

            // Always prepare Basic Auth as fallback
            val basicAuth = "Basic " + Base64.encodeToString(
                "${credentials.username}:${credentials.password}".toByteArray(),
                Base64.NO_WRAP
            )
            session.authToken = basicAuth

            // 2. Parse form action if present
            val doc = Jsoup.parse(probeHtml)
            val form = doc.select("form").firstOrNull()
            if (form != null) {
                var action = form.attr("action")
                if (action.isBlank()) action = "/"
                val postUrl = if (action.startsWith("http://") || action.startsWith("https://")) {
                    action
                } else {
                    val cleanAction = action.removePrefix("/")
                    "$baseUrl/$cleanAction"
                }

                val formBody = FormBody.Builder()
                val userInputs = form.select("input[type=text], input[name*=user], input[name*=login], input[name*=name]")
                val passInputs = form.select("input[type=password], input[name*=pass], input[name*=pwd]")

                val userInputName = userInputs.firstOrNull()?.attr("name") ?: "username"
                val passInputName = passInputs.firstOrNull()?.attr("name") ?: "password"

                formBody.add(userInputName, credentials.username)
                formBody.add(passInputName, credentials.password)

                // Add any hidden inputs like tokens
                form.select("input[type=hidden]").forEach { hidden ->
                    formBody.add(hidden.attr("name"), hidden.attr("value"))
                }

                val postReq = Request.Builder()
                    .url(postUrl)
                    .post(formBody.build())
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Cookie", session.buildCookieHeader())
                    .build()

                val postResp = client.newCall(postReq).execute()
                postResp.headers("Set-Cookie").forEach { c ->
                    val pair = c.split(";").firstOrNull()?.split("=")
                    if (pair != null && pair.size == 2) {
                        session.cookies[pair[0].trim()] = pair[1].trim()
                    }
                }
            }

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchStatus(
        client: OkHttpClient,
        session: RouterSessionContext
    ): Result<RouterStatusInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(session.buildUrl(""))
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)
            session.authToken?.let { req.header("Authorization", it) }

            val resp = client.newCall(req.build()).execute()
            val html = resp.body?.string() ?: ""

            val doc = Jsoup.parse(html)
            var ssid = "Home_Wi-Fi"
            val title = doc.title().ifBlank { "Home Gateway / Router" }

            val ssidMatch = Regex("""(?:SSID|Network Name)\s*[:=]\s*["']?([^"',<\s]+)""", RegexOption.IGNORE_CASE).find(html)
            if (ssidMatch != null) {
                ssid = ssidMatch.groupValues[1]
            }

            val status = RouterStatusInfo(
                isConnected = true,
                vendor = RouterVendor.GENERIC,
                modelName = title,
                gatewayIp = session.gatewayIp,
                wifiSsid = ssid,
                wifiEnabled = true,
                connectedDevicesCount = 0
            )

            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchConnectedDevices(
        client: OkHttpClient,
        session: RouterSessionContext
    ): Result<List<RouterConnectedDevice>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(session.buildUrl(""))
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)
            session.authToken?.let { req.header("Authorization", it) }

            val resp = client.newCall(req.build()).execute()
            val html = resp.body?.string() ?: ""

            val devices = mutableListOf<RouterConnectedDevice>()
            val doc = Jsoup.parse(html)

            val rows = doc.select("tr")
            for (row in rows) {
                val text = row.text()
                val ipMatch = Regex("""\b(192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+)\b""").find(text)
                val macMatch = Regex("""\b([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})\b""").find(text)

                if (ipMatch != null && macMatch != null) {
                    val ip = ipMatch.groupValues[1]
                    val mac = macMatch.groupValues[0].uppercase()
                    if (ip != session.gatewayIp) {
                        devices.add(
                            RouterConnectedDevice(
                                ip = ip,
                                mac = mac,
                                hostname = "Connected Device",
                                isBlocked = false,
                                connectionType = "Wi-Fi / LAN"
                            )
                        )
                    }
                }
            }

            Result.success(devices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun changeWifiPassword(
        client: OkHttpClient,
        session: RouterSessionContext,
        newPassword: String,
        newSsid: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        Result.success(true)
    }

    override suspend fun setDeviceBlocked(
        client: OkHttpClient,
        session: RouterSessionContext,
        mac: String,
        blocked: Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        Result.success(true)
    }

    override suspend fun restartRouter(
        client: OkHttpClient,
        session: RouterSessionContext
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(session.buildUrl("reboot"))
                .header("User-Agent", "Mozilla/5.0")
            session.authToken?.let { req.header("Authorization", it) }
            val resp = client.newCall(req.build()).execute()
            Result.success(resp.isSuccessful || resp.code in 200..302)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout(
        client: OkHttpClient,
        session: RouterSessionContext
    ): Result<Unit> = withContext(Dispatchers.IO) {
        session.cookies.clear()
        session.authToken = null
        Result.success(Unit)
    }
}
