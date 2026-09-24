package com.example.feature.router_control.strategy

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

class ZteRouterStrategy : RouterStrategy {

    override val vendor: RouterVendor = RouterVendor.ZTE

    override fun matches(html: String, headers: Map<String, List<String>>, serverHeader: String): Boolean {
        val lower = html.lowercase()
        val server = serverHeader.lowercase()
        return lower.contains("zte") ||
                lower.contains("zxhn") ||
                lower.contains("getpage.gch") ||
                lower.contains("f660") ||
                lower.contains("h108n") ||
                lower.contains("h168n") ||
                lower.contains("h188a") ||
                server.contains("zte")
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
                vendor = RouterVendor.ZTE,
                protocol = proto
            )

            val loginUrl = "$baseUrl/getpage.gch?pid=1002"
            val form = FormBody.Builder()
                .add("Username", credentials.username)
                .add("Password", credentials.password)
                .add("action", "login")
                .build()

            val req = Request.Builder()
                .url(loginUrl)
                .post(form)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val resp = client.newCall(req).execute()
            resp.headers("Set-Cookie").forEach { c ->
                val pair = c.split(";").firstOrNull()?.split("=")
                if (pair != null && pair.size == 2) {
                    session.cookies[pair[0].trim()] = pair[1].trim()
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
            val url = session.buildUrl("getpage.gch?pid=1002&nextpage=net_wlan_basic_t.gch")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)

            val resp = client.newCall(req.build()).execute()
            val html = resp.body?.string() ?: ""

            val doc = Jsoup.parse(html)
            val ssidElem = doc.select("input[name=ESSID], input[name=SSID]").firstOrNull()
            val ssid = ssidElem?.attr("value")?.ifBlank { null } ?: "ZTE_Home_Wi-Fi"

            val status = RouterStatusInfo(
                isConnected = true,
                vendor = RouterVendor.ZTE,
                modelName = "ZTE ZXHN Home Gateway",
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
            val url = session.buildUrl("getpage.gch?pid=1002&nextpage=net_dhcp_client_t.gch")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)

            val resp = client.newCall(req.build()).execute()
            val html = resp.body?.string() ?: ""

            val devices = mutableListOf<RouterConnectedDevice>()
            val doc = Jsoup.parse(html)

            val rows = doc.select("table tr")
            for (row in rows) {
                val cols = row.select("td")
                if (cols.size >= 3) {
                    val host = cols[0].text().trim()
                    val mac = cols[1].text().trim().replace("-", ":").uppercase()
                    val ip = cols[2].text().trim()

                    if (mac.matches(Regex("""^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"""))) {
                        devices.add(
                            RouterConnectedDevice(
                                ip = ip,
                                mac = mac,
                                hostname = host.ifBlank { "Client Device" },
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
        try {
            val url = session.buildUrl("getpage.gch?pid=1002&nextpage=net_wlan_security_t.gch")
            val form = FormBody.Builder()
                .add("WpaKey", newPassword)
                .add("action", "apply")
                .build()

            val req = Request.Builder()
                .url(url)
                .post(form)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)

            val resp = client.newCall(req.build()).execute()
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
            val url = session.buildUrl("getpage.gch?pid=1002&nextpage=sec_mac_filter_t.gch")
            val form = FormBody.Builder()
                .add("MacAddress", mac)
                .add("action", if (blocked) "block" else "unblock")
                .build()

            val req = Request.Builder()
                .url(url)
                .post(form)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)

            val resp = client.newCall(req.build()).execute()
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
            val url = session.buildUrl("getpage.gch?pid=1002&nextpage=man_reboot_t.gch")
            val form = FormBody.Builder()
                .add("action", "reboot")
                .build()

            val req = Request.Builder()
                .url(url)
                .post(form)
                .header("User-Agent", "Mozilla/5.0")

            val cookie = session.buildCookieHeader()
            if (cookie.isNotBlank()) req.header("Cookie", cookie)

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
        Result.success(Unit)
    }
}
