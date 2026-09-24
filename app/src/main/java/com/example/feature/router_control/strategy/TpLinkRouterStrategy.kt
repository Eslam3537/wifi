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
import java.io.IOException

class TpLinkRouterStrategy : RouterStrategy {

    override val vendor: RouterVendor = RouterVendor.TP_LINK

    override fun matches(html: String, headers: Map<String, List<String>>, serverHeader: String): Boolean {
        val lowerHtml = html.lowercase()
        val server = serverHeader.lowercase()
        return lowerHtml.contains("tp-link") ||
                lowerHtml.contains("tplink") ||
                lowerHtml.contains("tplinkwifi.net") ||
                lowerHtml.contains("/userrpm/") ||
                lowerHtml.contains("td-w") ||
                lowerHtml.contains("tl-wr") ||
                lowerHtml.contains("archer") ||
                server.contains("tp-link")
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

            val authHeader = "Basic " + Base64.encodeToString(
                "${credentials.username}:${credentials.password}".toByteArray(),
                Base64.NO_WRAP
            )

            // Test authentication on status or login page
            val testUrl = "$baseUrl/userRpm/StatusRpm.htm"
            val request = Request.Builder()
                .url(testUrl)
                .header("Authorization", authHeader)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 401 || response.code == 403) {
                return@withContext Result.failure(IOException("Invalid TP-Link router credentials."))
            }

            val session = RouterSessionContext(
                gatewayIp = cleanIp,
                credentials = credentials,
                vendor = RouterVendor.TP_LINK,
                protocol = proto,
                authToken = authHeader
            )

            // Capture any cookies from headers
            response.headers("Set-Cookie").forEach { cookieHeader ->
                val parts = cookieHeader.split(";").firstOrNull()?.split("=")
                if (parts != null && parts.size == 2) {
                    session.cookies[parts[0].trim()] = parts[1].trim()
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
            val url = session.buildUrl("userRpm/StatusRpm.htm")
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

            session.authToken?.let { reqBuilder.header("Authorization", it) }
            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

            val resp = client.newCall(reqBuilder.build()).execute()
            val html = resp.body?.string() ?: ""

            val doc = Jsoup.parse(html)
            var ssid = "TP-Link_Wi-Fi"
            var wifiEnabled = true
            var modelName = "TP-Link Wireless Router"

            // TP-Link web interfaces often declare JS variables e.g. wlanPara or statusPara
            val ssidRegex = Regex(""""([^"]+)"""")
            val wlanMatch = Regex("""wlanPara\s*=\s*new Array\s*\(([^)]+)\)""").find(html)
            if (wlanMatch != null) {
                val arrayContent = wlanMatch.groupValues[1]
                val tokens = ssidRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
                if (tokens.isNotEmpty()) {
                    ssid = tokens.firstOrNull { it.isNotBlank() && it.length > 2 && !it.contains(".") } ?: ssid
                }
            } else {
                // Parse standard HTML table labels
                val rows = doc.select("tr")
                for (row in rows) {
                    val text = row.text()
                    if (text.contains("SSID", ignoreCase = true) || text.contains("Wireless Network Name", ignoreCase = true)) {
                        val tdList = row.select("td")
                        if (tdList.size >= 2) {
                            ssid = tdList[1].text().trim().ifBlank { ssid }
                        }
                    }
                    if (text.contains("Model", ignoreCase = true)) {
                        val tdList = row.select("td")
                        if (tdList.size >= 2) {
                            modelName = tdList[1].text().trim().ifBlank { modelName }
                        }
                    }
                }
            }

            val status = RouterStatusInfo(
                isConnected = true,
                vendor = RouterVendor.TP_LINK,
                modelName = modelName,
                gatewayIp = session.gatewayIp,
                wifiSsid = ssid,
                wifiEnabled = wifiEnabled,
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
            val url = session.buildUrl("userRpm/AssignedIpAddrListRpm.htm")
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            session.authToken?.let { reqBuilder.header("Authorization", it) }
            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

            val resp = client.newCall(reqBuilder.build()).execute()
            val html = resp.body?.string() ?: ""

            val devices = mutableListOf<RouterConnectedDevice>()

            // 1. Check JS array DHCP list e.g. DHCPDynList = new Array("Client1", "00-11-22-33-44-55", "192.168.1.100", "01:23:45", ...)
            val arrayMatch = Regex("""DHCPDynList\s*=\s*new Array\s*\(([^)]+)\)""").find(html)
            if (arrayMatch != null) {
                val rawTokens = arrayMatch.groupValues[1]
                val tokens = Regex(""""([^"]*)"""").findAll(rawTokens).map { it.groupValues[1].trim() }.toList()

                var i = 0
                while (i + 3 < tokens.size) {
                    val hostname = tokens[i].ifBlank { "Unknown Device" }
                    val mac = tokens[i + 1].replace("-", ":").uppercase()
                    val ip = tokens[i + 2]
                    val lease = tokens[i + 3]

                    if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        devices.add(
                            RouterConnectedDevice(
                                ip = ip,
                                mac = mac,
                                hostname = hostname,
                                isBlocked = false,
                                connectionType = "Wi-Fi / LAN",
                                leaseTime = lease
                            )
                        )
                    }
                    i += 4
                }
            } else {
                // 2. Parse HTML tables
                val doc = Jsoup.parse(html)
                val rows = doc.select("table tr")
                for (row in rows) {
                    val cols = row.select("td")
                    if (cols.size >= 4) {
                        val host = cols[0].text().trim()
                        val mac = cols[1].text().trim().replace("-", ":").uppercase()
                        val ip = cols[2].text().trim()
                        val lease = cols[3].text().trim()

                        if (mac.matches(Regex("""^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"""))) {
                            devices.add(
                                RouterConnectedDevice(
                                    ip = ip,
                                    mac = mac,
                                    hostname = host.ifBlank { "Client Device" },
                                    isBlocked = false,
                                    connectionType = "Wi-Fi / LAN",
                                    leaseTime = lease
                                )
                            )
                        }
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
            val url = session.buildUrl("userRpm/WlanSecurityRpm.htm")
            val form = FormBody.Builder()
                .add("secType", "3") // WPA2-PSK
                .add("pskSecret", newPassword)
                .add("Save", "Save")
                .build()

            val reqBuilder = Request.Builder()
                .url(url)
                .post(form)
                .header("User-Agent", "Mozilla/5.0")

            session.authToken?.let { reqBuilder.header("Authorization", it) }
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
            val url = session.buildUrl("userRpm/WlanMacFilterRpm.htm")
            val form = FormBody.Builder()
                .add("Mac", mac.replace(":", "-"))
                .add("Desc", "Blocked_via_App")
                .add("Status", if (blocked) "1" else "0")
                .add("Save", "Save")
                .build()

            val reqBuilder = Request.Builder()
                .url(url)
                .post(form)
                .header("User-Agent", "Mozilla/5.0")

            session.authToken?.let { reqBuilder.header("Authorization", it) }
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
            val url = session.buildUrl("userRpm/SysRebootRpm.htm?Reboot=Reboot")
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            session.authToken?.let { reqBuilder.header("Authorization", it) }
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
