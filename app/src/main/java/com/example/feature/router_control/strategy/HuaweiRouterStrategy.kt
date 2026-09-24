package com.example.feature.router_control.strategy

import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.security.MessageDigest

class HuaweiRouterStrategy : RouterStrategy {

    override val vendor: RouterVendor = RouterVendor.HUAWEI

    override fun matches(html: String, headers: Map<String, List<String>>, serverHeader: String): Boolean {
        val lower = html.lowercase()
        val server = serverHeader.lowercase()
        return lower.contains("huawei") ||
                lower.contains("hg630") ||
                lower.contains("hg658") ||
                lower.contains("echolife") ||
                lower.contains("dn8245") ||
                lower.contains("huaweihomegateway") ||
                server.contains("huaweihomegateway") ||
                server.contains("huawei")
    }

    override suspend fun login(
        client: OkHttpClient,
        gatewayIp: String,
        credentials: RouterCredentials
    ): Result<RouterSessionContext> = withContext(Dispatchers.IO) {
        try {
            // First GET initial page to extract CSRF token / challenge if present
            val initialUrl = "http://$gatewayIp/"
            val initialReq = Request.Builder()
                .url(initialUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val initialResp = client.newCall(initialReq).execute()
            val initialHtml = initialResp.body?.string() ?: ""

            val session = RouterSessionContext(
                gatewayIp = gatewayIp,
                credentials = credentials,
                vendor = RouterVendor.HUAWEI
            )

            // Collect initial cookies
            initialResp.headers("Set-Cookie").forEach { c ->
                val pair = c.split(";").firstOrNull()?.split("=")
                if (pair != null && pair.size == 2) {
                    session.cookies[pair[0].trim()] = pair[1].trim()
                }
            }

            // Extract token if present
            val tokenMatch = Regex("""name=['"]csrf_token['"]\s+value=['"]([^'"]+)['"]""").find(initialHtml)
            session.csrfToken = tokenMatch?.groupValues?.getOrNull(1)

            // Submit login POST
            val loginUrl = "http://$gatewayIp/index/login.cgi"
            val form = FormBody.Builder()
                .add("username", credentials.username)
                .add("password", credentials.password)
            session.csrfToken?.let { form.add("csrf_token", it) }

            val loginReq = Request.Builder()
                .url(loginUrl)
                .post(form.build())
                .header("Referer", initialUrl)
                .header("User-Agent", "Mozilla/5.0")

            val cookieStr = session.buildCookieHeader()
            if (cookieStr.isNotBlank()) loginReq.header("Cookie", cookieStr)

            val loginResp = client.newCall(loginReq.build()).execute()
            loginResp.headers("Set-Cookie").forEach { c ->
                val pair = c.split(";").firstOrNull()?.split("=")
                if (pair != null && pair.size == 2) {
                    session.cookies[pair[0].trim()] = pair[1].trim()
                }
            }

            val body = loginResp.body?.string() ?: ""
            if (body.contains("fail", ignoreCase = true) || body.contains("error", ignoreCase = true) && loginResp.code != 302) {
                // If form fails, check if HTTP Basic Auth works
                val basicAuth = "Basic " + android.util.Base64.encodeToString(
                    "${credentials.username}:${credentials.password}".toByteArray(),
                    android.util.Base64.NO_WRAP
                )
                session.authToken = basicAuth
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
            val url = "http://${session.gatewayIp}/html/bbsp/wlaninfo/wlaninfo.asp"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)
            session.authToken?.let { reqBuilder.header("Authorization", it) }

            val resp = client.newCall(reqBuilder.build()).execute()
            val html = resp.body?.string() ?: ""

            val doc = Jsoup.parse(html)
            var ssid = "Huawei_Home_Wi-Fi"
            var model = "Huawei HG630 / EchoLife"

            // Look for SSID inputs or text
            val ssidElem = doc.select("input[name=WlanSsid], input[name=ssid], input[name=SSID]").firstOrNull()
            if (ssidElem != null && ssidElem.attr("value").isNotBlank()) {
                ssid = ssidElem.attr("value")
            } else {
                val match = Regex("""(?:SSID|WlanSsid)\s*[:=]\s*["']?([^"',\s]+)""").find(html)
                if (match != null) ssid = match.groupValues[1]
            }

            val status = RouterStatusInfo(
                isConnected = true,
                vendor = RouterVendor.HUAWEI,
                modelName = model,
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
            val url = "http://${session.gatewayIp}/html/bbsp/userdevmngr/userdevmngr.asp"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)
            session.authToken?.let { reqBuilder.header("Authorization", it) }

            val resp = client.newCall(reqBuilder.build()).execute()
            val html = resp.body?.string() ?: ""

            val devices = mutableListOf<RouterConnectedDevice>()
            val doc = Jsoup.parse(html)

            // Extract table rows with IP and MAC
            val rows = doc.select("table tr")
            for (row in rows) {
                val text = row.text()
                val ipMatch = Regex("""\b(192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+)\b""").find(text)
                val macMatch = Regex("""\b([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})\b""").find(text)

                if (ipMatch != null && macMatch != null) {
                    val ip = ipMatch.groupValues[1]
                    val mac = macMatch.groupValues[0].replace("-", ":").uppercase()
                    if (ip != session.gatewayIp) {
                        val host = row.select("td").firstOrNull()?.text()?.trim() ?: "Device"
                        devices.add(
                            RouterConnectedDevice(
                                ip = ip,
                                mac = mac,
                                hostname = if (host.contains(".")) "Connected Client" else host,
                                isBlocked = text.contains("Block", ignoreCase = true) && !text.contains("Unblock", ignoreCase = true),
                                connectionType = if (text.contains("WLAN", ignoreCase = true) || text.contains("Wi-Fi", ignoreCase = true)) "Wi-Fi" else "Ethernet"
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
        try {
            val url = "http://${session.gatewayIp}/html/bbsp/wlaninfo/setwlan.cgi"
            val form = FormBody.Builder()
                .add("WpaPskKey", newPassword)
            if (!newSsid.isNullOrBlank()) {
                form.add("WlanSsid", newSsid)
            }
            session.csrfToken?.let { form.add("csrf_token", it) }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(form.build())
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

            val resp = client.newCall(reqBuilder.build()).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setDeviceBlocked(
        client: OkHttpClient,
        session: RouterSessionContext,
        mac: String,
        blocked: Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "http://${session.gatewayIp}/html/bbsp/macfilter/setmacfilter.cgi"
            val form = FormBody.Builder()
                .add("MacAddress", mac)
                .add("Action", if (blocked) "Add" else "Delete")
            session.csrfToken?.let { form.add("csrf_token", it) }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(form.build())
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

            val resp = client.newCall(reqBuilder.build()).execute()
            Result.success(resp.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun restartRouter(
        client: OkHttpClient,
        session: RouterSessionContext
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "http://${session.gatewayIp}/html/management/reboot.cgi"
            val form = FormBody.Builder()
                .add("Reboot", "1")
            session.csrfToken?.let { form.add("csrf_token", it) }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(form.build())
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

            val resp = client.newCall(reqBuilder.build()).execute()
            Result.success(resp.isSuccessful || resp.code in 200..302)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout(
        client: OkHttpClient,
        session: RouterSessionContext
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            session.cookies.clear()
            session.authToken = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
