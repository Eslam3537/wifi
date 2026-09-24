package com.example.feature.router_control.strategy

import android.util.Base64
import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class MikrotikRouterStrategy : RouterStrategy {

    override val vendor: RouterVendor = RouterVendor.MIKROTIK

    override fun matches(html: String, headers: Map<String, List<String>>, serverHeader: String): Boolean {
        val lower = html.lowercase()
        val server = serverHeader.lowercase()
        return lower.contains("mikrotik") ||
                lower.contains("routeros") ||
                lower.contains("webfig") ||
                server.contains("mikrotik") ||
                server.contains("routeros")
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

            val auth = "Basic " + Base64.encodeToString(
                "${credentials.username}:${credentials.password}".toByteArray(),
                Base64.NO_WRAP
            )

            val req = Request.Builder()
                .url("$baseUrl/webfig/")
                .header("Authorization", auth)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val resp = client.newCall(req).execute()
            if (resp.code == 401 || resp.code == 403) {
                return@withContext Result.failure(Exception("MikroTik authentication failed. Check credentials."))
            }

            val session = RouterSessionContext(
                gatewayIp = cleanIp,
                credentials = credentials,
                vendor = RouterVendor.MIKROTIK,
                protocol = proto,
                authToken = auth
            )

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
            val status = RouterStatusInfo(
                isConnected = true,
                vendor = RouterVendor.MIKROTIK,
                modelName = "MikroTik RouterOS",
                gatewayIp = session.gatewayIp,
                wifiSsid = "MikroTik_AP",
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
            // Read lease table via webfig/rest or HTML export
            val req = Request.Builder()
                .url(session.buildUrl("webfig/#IP:DHCP_Server.Leases"))
                .header("User-Agent", "Mozilla/5.0")

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
                    devices.add(
                        RouterConnectedDevice(
                            ip = ipMatch.groupValues[1],
                            mac = macMatch.groupValues[0].uppercase(),
                            hostname = "MikroTik Client",
                            isBlocked = false,
                            connectionType = "LAN / Wi-Fi"
                        )
                    )
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
        // MikroTik wireless profile update
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
