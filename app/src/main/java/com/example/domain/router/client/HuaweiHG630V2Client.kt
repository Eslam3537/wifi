package com.example.domain.router.client

import android.util.Base64
import com.example.domain.router.model.*
import com.example.domain.router.session.RouterSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Native client implementation for Huawei Home Gateway HG630 V2 / HG658 / EchoLife series.
 * Handles challenge authentication, CSRF tokens, session cookies, and native dashboard telemetry.
 */
class HuaweiHG630V2Client(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
) : RouterClient {

    override val supportedModelName: String = "Huawei HG630 V2 (Home Gateway)"

    override suspend fun probe(gatewayIp: String): Boolean = withContext(Dispatchers.IO) {
        if (!RouterSessionManager.isLocalLanIp(gatewayIp)) return@withContext false
        try {
            val req = Request.Builder()
                .url("http://$gatewayIp/")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; NetManagerPro)")
                .build()

            val res = client.newCall(req).execute()
            val serverHeader = res.header("Server") ?: ""
            val body = res.body?.string() ?: ""

            body.contains("HG630", ignoreCase = true) ||
                    body.contains("HuaweiHomeGateway", ignoreCase = true) ||
                    body.contains("Huawei Technologies", ignoreCase = true) ||
                    serverHeader.contains("HuaweiHomeGateway", ignoreCase = true) ||
                    body.contains("EchoLife", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun login(gatewayIp: String, credentials: RouterCredentials): Result<RouterSession> = withContext(Dispatchers.IO) {
        if (!RouterSessionManager.isLocalLanIp(gatewayIp)) {
            return@withContext Result.failure(IllegalArgumentException("Security Violation: Router IP must be a valid local LAN address (e.g. 192.168.1.1)."))
        }

        try {
            // Step 1: Initialize session by fetching index page to obtain initial Cookies & CSRF Token
            val initReq = Request.Builder()
                .url("http://$gatewayIp/html/index.html")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; NetManagerPro)")
                .build()

            val initRes = try {
                client.newCall(initReq).execute()
            } catch (e: Exception) {
                // Try root URL fallback
                val fallbackReq = Request.Builder().url("http://$gatewayIp/").build()
                client.newCall(fallbackReq).execute()
            }

            val initBody = initRes.body?.string() ?: ""
            var session = RouterSessionManager.extractSessionFromResponse(gatewayIp, initRes, initBody)

            // Step 2: Prepare credentials with Huawei HG630 V2 SHA-256 + Token Challenge
            val csrfToken = session.csrfToken ?: ""
            val hashedPassword = calculateHuaweiPassword(credentials.username, credentials.password, csrfToken)

            // Step 3: Attempt API-based JSON login (Primary HG630 V2 method)
            val jsonPayload = JSONObject().apply {
                put("UserName", credentials.username)
                put("Password", hashedPassword)
                if (csrfToken.isNotBlank()) {
                    put("csrf_param", csrfToken)
                    put("csrf_token", csrfToken)
                }
            }.toString()

            val jsonReq = Request.Builder()
                .url("http://$gatewayIp/api/system/user_login")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Cookie", RouterSessionManager.buildCookieHeader(session))
                .apply {
                    if (csrfToken.isNotBlank()) {
                        header("Csrf-Token", csrfToken)
                        header("X-CSRF-Token", csrfToken)
                    }
                }
                .build()

            val jsonRes = client.newCall(jsonReq).execute()
            val jsonBody = jsonRes.body?.string() ?: ""

            if (jsonRes.isSuccessful && isLoginSuccessful(jsonBody, jsonRes)) {
                val updatedSession = RouterSessionManager.extractSessionFromResponse(gatewayIp, jsonRes, jsonBody)
                val mergedCookies = session.cookies.toMutableMap().apply { putAll(updatedSession.cookies) }
                val finalSession = updatedSession.copy(
                    cookies = mergedCookies,
                    csrfToken = updatedSession.csrfToken ?: session.csrfToken
                )
                RouterSessionManager.setActiveSession(finalSession)
                return@withContext Result.success(finalSession)
            }

            // Step 4: Fallback to CGI Form login (Classic firmware variant)
            val formBody = FormBody.Builder()
                .add("username", credentials.username)
                .add("password", credentials.password)
                .apply {
                    if (csrfToken.isNotBlank()) {
                        add("CsrfToken", csrfToken)
                        add("csrf_token", csrfToken)
                    }
                }
                .build()

            val formReq = Request.Builder()
                .url("http://$gatewayIp/index/login.cgi")
                .post(formBody)
                .header("Cookie", RouterSessionManager.buildCookieHeader(session))
                .build()

            val formRes = client.newCall(formReq).execute()
            val formResBody = formRes.body?.string() ?: ""

            if (formRes.isSuccessful && (formRes.code == 302 || isLoginSuccessful(formResBody, formRes))) {
                val updatedSession = RouterSessionManager.extractSessionFromResponse(gatewayIp, formRes, formResBody)
                val mergedCookies = session.cookies.toMutableMap().apply { putAll(updatedSession.cookies) }
                val finalSession = updatedSession.copy(cookies = mergedCookies)
                RouterSessionManager.setActiveSession(finalSession)
                return@withContext Result.success(finalSession)
            }

            // Parse specific Huawei error codes if returned
            val errorMessage = extractLoginErrorMessage(jsonBody)
            Result.failure(SecurityException(errorMessage))

        } catch (e: Exception) {
            val friendly = mapNetworkException(e, gatewayIp)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun getDashboardData(session: RouterSession): Result<RouterDashboardData> = withContext(Dispatchers.IO) {
        if (session.isExpired) {
            return@withContext Result.failure(IllegalStateException("Router session expired. Please reconnect."))
        }

        try {
            val gatewayIp = session.gatewayIp
            val cookieHeader = RouterSessionManager.buildCookieHeader(session)

            // 1. Fetch System & Device Info
            val devInfo = fetchDeviceInfo(gatewayIp, cookieHeader, session.csrfToken)

            // 2. Fetch WAN & Internet Info
            val wanInfo = fetchWanInfo(gatewayIp, cookieHeader, session.csrfToken)

            // 3. Fetch LAN Info
            val lanInfo = fetchLanInfo(gatewayIp, cookieHeader, session.csrfToken)

            // 4. Fetch Wi-Fi Settings & Radio Status
            val wifiInfo = fetchWifiInfo(gatewayIp, cookieHeader, session.csrfToken)

            // 5. Fetch Connected Clients (LAN + Wi-Fi)
            val devices = fetchConnectedClients(gatewayIp, cookieHeader, session.csrfToken)

            val dashboard = RouterDashboardData(
                deviceInfo = devInfo,
                wanInfo = wanInfo,
                lanInfo = lanInfo,
                wifiInfo = wifiInfo,
                connectedDevices = devices,
                lastUpdated = System.currentTimeMillis()
            )

            Result.success(dashboard)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getConnectedDevices(session: RouterSession): Result<List<RouterConnectedDevice>> = withContext(Dispatchers.IO) {
        try {
            val devices = fetchConnectedClients(session.gatewayIp, RouterSessionManager.buildCookieHeader(session), session.csrfToken)
            Result.success(devices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getWifiSettings(session: RouterSession): Result<RouterWifiInfo> = withContext(Dispatchers.IO) {
        try {
            val wifi = fetchWifiInfo(session.gatewayIp, RouterSessionManager.buildCookieHeader(session), session.csrfToken)
            Result.success(wifi)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateWifiSettings(session: RouterSession, payload: WifiUpdatePayload): Result<Boolean> = withContext(Dispatchers.IO) {
        if (session.isExpired) return@withContext Result.failure(IllegalStateException("Session expired"))

        try {
            val json = JSONObject().apply {
                put("SSID", payload.ssid)
                if (payload.password != null) put("Key", payload.password)
                if (payload.enabled != null) put("WlanEnable", if (payload.enabled) 1 else 0)
                if (payload.channel != null) put("Channel", payload.channel)
                if (!session.csrfToken.isNullOrBlank()) {
                    put("csrf_param", session.csrfToken)
                    put("csrf_token", session.csrfToken)
                }
            }.toString()

            val req = Request.Builder()
                .url("http://${session.gatewayIp}/api/ntwk/WlanBasic")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Cookie", RouterSessionManager.buildCookieHeader(session))
                .apply {
                    if (!session.csrfToken.isNullOrBlank()) {
                        header("Csrf-Token", session.csrfToken)
                    }
                }
                .build()

            val res = client.newCall(req).execute()
            val body = res.body?.string() ?: ""

            if (res.isSuccessful && (body.contains("\"error\":0") || body.contains("\"success\":true") || body.contains("OK"))) {
                Result.success(true)
            } else {
                Result.failure(RuntimeException("Router rejected Wi-Fi configuration change: ${extractErrorMessage(body)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reboot(session: RouterSession): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("reboot", 1)
                if (!session.csrfToken.isNullOrBlank()) put("csrf_token", session.csrfToken)
            }.toString()

            val req = Request.Builder()
                .url("http://${session.gatewayIp}/api/system/device_reboot")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Cookie", RouterSessionManager.buildCookieHeader(session))
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                RouterSessionManager.clearSession()
                Result.success(true)
            } else {
                Result.failure(RuntimeException("Reboot command failed with status ${res.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout(session: RouterSession): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("http://${session.gatewayIp}/api/system/user_logout")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Cookie", RouterSessionManager.buildCookieHeader(session))
                .build()

            client.newCall(req).execute()
            RouterSessionManager.clearSession()
            Result.success(true)
        } catch (e: Exception) {
            RouterSessionManager.clearSession()
            Result.success(true)
        }
    }

    // ==========================================
    // Telemetry Fetchers & Parsers (Safe Parsing)
    // ==========================================

    private fun fetchDeviceInfo(gatewayIp: String, cookie: String, csrfToken: String?): RouterDeviceInfo {
        val endpoints = listOf("/api/system/deviceinfo", "/api/system/status", "/api/system/HostInfo")
        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url("http://$gatewayIp$ep")
                    .header("Cookie", cookie)
                    .apply { if (!csrfToken.isNullOrBlank()) header("Csrf-Token", csrfToken) }
                    .build()

                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val info = parseDeviceInfo(body)
                    if (info.model != "Not available" || info.firmwareVersion != "Not available") {
                        return info
                    }
                }
            } catch (_: Exception) {}
        }
        return RouterDeviceInfo(model = "Huawei HG630 V2")
    }

    private fun fetchWanInfo(gatewayIp: String, cookie: String, csrfToken: String?): RouterWanInfo {
        val endpoints = listOf("/api/ntwk/wan_info", "/api/system/wan_status", "/api/ntwk/wan")
        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url("http://$gatewayIp$ep")
                    .header("Cookie", cookie)
                    .apply { if (!csrfToken.isNullOrBlank()) header("Csrf-Token", csrfToken) }
                    .build()

                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val wan = parseWanInfo(body)
                    if (wan.ip != "Not available" || wan.status != "Not available") {
                        return wan
                    }
                }
            } catch (_: Exception) {}
        }
        return RouterWanInfo()
    }

    private fun fetchLanInfo(gatewayIp: String, cookie: String, csrfToken: String?): RouterLanInfo {
        try {
            val req = Request.Builder()
                .url("http://$gatewayIp/api/ntwk/lan_info")
                .header("Cookie", cookie)
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                return parseLanInfo(res.body?.string() ?: "")
            }
        } catch (_: Exception) {}
        return RouterLanInfo(ip = gatewayIp, subnet = "255.255.255.0", dhcpEnabled = true)
    }

    private fun fetchWifiInfo(gatewayIp: String, cookie: String, csrfToken: String?): RouterWifiInfo {
        val endpoints = listOf("/api/ntwk/WlanBasic", "/api/ntwk/wlan_basic_info", "/api/ntwk/wifi_status")
        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url("http://$gatewayIp$ep")
                    .header("Cookie", cookie)
                    .build()

                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val wifi = parseWifiInfo(body)
                    if (wifi.ssid != "Not available") return wifi
                }
            } catch (_: Exception) {}
        }
        return RouterWifiInfo()
    }

    private fun fetchConnectedClients(gatewayIp: String, cookie: String, csrfToken: String?): List<RouterConnectedDevice> {
        val endpoints = listOf("/api/ntwk/lan_device_info", "/api/ntwk/HostList", "/api/ntwk/dhcp_client_list")
        for (ep in endpoints) {
            try {
                val req = Request.Builder()
                    .url("http://$gatewayIp$ep")
                    .header("Cookie", cookie)
                    .build()

                val res = client.newCall(req).execute()
                if (res.isSuccessful) {
                    val list = parseConnectedDevices(res.body?.string() ?: "")
                    if (list.isNotEmpty()) return list
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    // ==========================================
    // Public Parsers (Extracted for Unit Testing)
    // ==========================================

    fun parseDeviceInfo(body: String): RouterDeviceInfo {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        if (json != null) {
            val model = json.optString("ModelName", json.optString("ProductClass", "Huawei HG630 V2"))
            val fw = json.optString("SoftwareVersion", json.optString("Software_Version", "Not available"))
            val hw = json.optString("HardwareVersion", json.optString("Hardware_Version", "Not available"))
            val sn = json.optString("SerialNumber", json.optString("Serial_Number", "Not available"))
            val uptime = json.optLong("UpTime", json.optLong("Up_Time", 0L))
            val devName = json.optString("DeviceName", "HG630 V2 Home Gateway")

            return RouterDeviceInfo(
                model = if (model.isNotBlank()) model else "Huawei HG630 V2",
                firmwareVersion = if (fw.isNotBlank()) fw else "Not available",
                hardwareVersion = if (hw.isNotBlank()) hw else "Not available",
                serialNumber = if (sn.isNotBlank()) sn else "Not available",
                uptimeSeconds = uptime,
                formattedUptime = formatUptime(uptime),
                deviceName = devName
            )
        }

        // XML fallback
        val model = extractXmlValue(body, "ModelName") ?: extractXmlValue(body, "ProductClass") ?: "Huawei HG630 V2"
        val fw = extractXmlValue(body, "SoftwareVersion") ?: "Not available"
        val hw = extractXmlValue(body, "HardwareVersion") ?: "Not available"
        val sn = extractXmlValue(body, "SerialNumber") ?: "Not available"
        val uptime = extractXmlValue(body, "UpTime")?.toLongOrNull() ?: 0L

        return RouterDeviceInfo(
            model = model,
            firmwareVersion = fw,
            hardwareVersion = hw,
            serialNumber = sn,
            uptimeSeconds = uptime,
            formattedUptime = formatUptime(uptime),
            deviceName = "HG630 V2 Home Gateway"
        )
    }

    fun parseWanInfo(body: String): RouterWanInfo {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        if (json != null) {
            val ip = json.optString("ExternalIPAddress", json.optString("IPAddress", "Not available"))
            val mask = json.optString("SubnetMask", "Not available")
            val gw = json.optString("DefaultGateway", "Not available")
            val dns = json.optString("DNSServers", "")
            val dnsList = dns.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val status = json.optString("ConnectionStatus", json.optString("Status", "Connected"))
            val connType = json.optString("ConnectionType", "PPPoE / IPoE")
            val mac = json.optString("MACAddress", "Not available")

            return RouterWanInfo(
                status = status,
                connectionType = connType,
                ip = ip,
                subnet = mask,
                gateway = gw,
                primaryDns = dnsList.getOrNull(0) ?: "Not available",
                secondaryDns = dnsList.getOrNull(1) ?: "Not available",
                mac = mac
            )
        }

        return RouterWanInfo(
            status = extractXmlValue(body, "ConnectionStatus") ?: "Connected",
            connectionType = extractXmlValue(body, "ConnectionType") ?: "PPPoE",
            ip = extractXmlValue(body, "ExternalIPAddress") ?: extractXmlValue(body, "IPAddress") ?: "Not available",
            subnet = extractXmlValue(body, "SubnetMask") ?: "Not available",
            gateway = extractXmlValue(body, "DefaultGateway") ?: "Not available",
            primaryDns = extractXmlValue(body, "DNSServers")?.split(",")?.getOrNull(0) ?: "Not available",
            secondaryDns = extractXmlValue(body, "DNSServers")?.split(",")?.getOrNull(1) ?: "Not available",
            mac = extractXmlValue(body, "MACAddress") ?: "Not available"
        )
    }

    fun parseLanInfo(body: String): RouterLanInfo {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        if (json != null) {
            return RouterLanInfo(
                ip = json.optString("IPInterfaceIPAddress", "192.168.1.1"),
                subnet = json.optString("IPInterfaceSubnetMask", "255.255.255.0"),
                dhcpEnabled = json.optBoolean("DHCPServerEnable", true),
                dhcpStart = json.optString("MinAddress", "192.168.1.2"),
                dhcpEnd = json.optString("MaxAddress", "192.168.1.254")
            )
        }
        return RouterLanInfo(ip = "192.168.1.1", subnet = "255.255.255.0", dhcpEnabled = true)
    }

    fun parseWifiInfo(body: String): RouterWifiInfo {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        if (json != null) {
            val enabled = json.optInt("WlanEnable", if (json.optBoolean("Enable", true)) 1 else 0) == 1
            val ssid = json.optString("SSID", json.optString("ssid", "Not available"))
            val channel = json.optString("Channel", "Auto")
            val sec = json.optString("BeaconType", json.optString("SecurityMode", "WPA2-PSK (AES)"))
            val standard = json.optString("Standard", "802.11b/g/n")
            val power = json.optString("TransmitPower", "100%")

            return RouterWifiInfo(
                enabled = enabled,
                ssid = ssid,
                channel = channel,
                securityMode = sec,
                standard = standard,
                transmitPower = power
            )
        }

        val ssid = extractXmlValue(body, "SSID") ?: "Not available"
        val enabled = extractXmlValue(body, "WlanEnable") != "0"
        return RouterWifiInfo(enabled = enabled, ssid = ssid)
    }

    fun parseConnectedDevices(body: String): List<RouterConnectedDevice> {
        val list = mutableListOf<RouterConnectedDevice>()
        try {
            val root = JSONObject(body)
            val array = root.optJSONArray("Host") 
                ?: root.optJSONArray("HostList") 
                ?: root.optJSONArray("hosts") 
                ?: root.optJSONArray("devices")
                ?: root.optJSONArray("Clients")

            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("HostName", obj.optString("DeviceName", "Unknown Device"))
                    val ip = obj.optString("IPAddress", obj.optString("ip", "Not available"))
                    val mac = obj.optString("MACAddress", obj.optString("mac", "Not available"))
                    val rawType = obj.optString("InterfaceType", "")
                    val isEthernet = rawType.contains("Ethernet", ignoreCase = true) || rawType.contains("LAN", ignoreCase = true) || (obj.has("IsWireless") && !obj.optBoolean("IsWireless"))
                    val type = if (isEthernet) "Ethernet (LAN)" else "Wi-Fi (2.4 GHz)"
                    val active = obj.optInt("Active", if (obj.optBoolean("Active", true)) 1 else 0) == 1
                    val lease = obj.optString("LeaseTimeRemaining", "Active")

                    list.add(
                        RouterConnectedDevice(
                            name = if (name.isNotBlank()) name else "Host ($ip)",
                            ip = ip,
                            mac = mac,
                            connectionType = type,
                            isOnline = active,
                            leaseTime = lease
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private fun calculateHuaweiPassword(user: String, pass: String, token: String): String {
        val passSha256 = sha256Hex(pass)
        val passBase64 = Base64.encodeToString(passSha256.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return if (token.isNotBlank()) {
            sha256Hex("$user$passBase64$token")
        } else {
            passSha256
        }
    }

    fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isLoginSuccessful(body: String, res: Response): Boolean {
        if (res.code == 302 || res.code == 301) return true
        if (body.contains("\"error\":0") || body.contains("\"error\": 0")) return true
        if (body.contains("\"success\":true") || body.contains("\"success\": true")) return true
        if (body.contains("<response>OK</response>", ignoreCase = true)) return true
        if (body.contains("/html/main.html", ignoreCase = true)) return true
        return false
    }

    private fun extractLoginErrorMessage(body: String): String {
        if (body.contains("108001") || body.contains("password", ignoreCase = true)) {
            return "Invalid router credentials. Please check your username and password."
        }
        if (body.contains("108002") || body.contains("locked", ignoreCase = true)) {
            return "Router login temporarily locked due to multiple failed attempts. Please wait 1 minute."
        }
        if (body.contains("108003") || body.contains("logged in", ignoreCase = true)) {
            return "Another admin session is active on this router. Please log out from other devices."
        }
        return "Router authentication failed. Please verify credentials."
    }

    private fun extractErrorMessage(body: String): String {
        val pattern = Pattern.compile("[\"'](?:error_desc|message|msg)[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
        val matcher = pattern.matcher(body)
        if (matcher.find()) return matcher.group(1) ?: "Unknown error"
        return "Operation declined by gateway firmware"
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val pattern = Pattern.compile("<$tag>([^<]+)</$tag>", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(xml)
        if (matcher.find()) return matcher.group(1)
        return null
    }

    private fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "Not available"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m ${seconds % 60}s"
        }
    }

    private fun mapNetworkException(e: Throwable, gatewayIp: String): String {
        val msg = e.localizedMessage ?: e.message ?: ""
        val isEmulator = android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.FINGERPRINT.startsWith("unknown") ||
                android.os.Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                android.os.Build.MODEL.contains("Emulator", ignoreCase = true) ||
                android.os.Build.MODEL.contains("Android SDK", ignoreCase = true) ||
                android.os.Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                android.os.Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
                msg.contains("10.0.2.")

        return when {
            e is java.net.SocketTimeoutException || msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) -> {
                if (isEmulator) {
                    "انتهت مهلة الاتصال بالراوتر ($gatewayIp).\n• تنبيه: التطبيق يعمل حالياً داخل محاكي (Emulator)، والمحاكي معزول سحابياً ولا يستطيع الاتصال بالراوتر المنزلي مباشرة.\n• لتجربة الراوتر الفعلي: ثبّت ملف الـ APK على هاتفك المتصل بنفس شبكة الواي فاي للراوتر.\n• أو اضغط على 'وضع المحاكاة التجريبي (Demo Mode)' لاستعراض لوحة التحكم والميزات الآن."
                } else {
                    "انتهت مهلة الاتصال بالراوتر ($gatewayIp).\n• تأكد من تشغيل الراوتر واتصال هاتفك بالواي فاي المنزلي وليس باقة الهاتف.\n• تأكد من صحة عنوان IP للراوتر (افتراضياً 192.168.1.1)."
                }
            }
            e is java.net.ConnectException || msg.contains("Connection refused", ignoreCase = true) || msg.contains("failed to connect", ignoreCase = true) -> {
                if (isEmulator) {
                    "تعذر الوصول إلى منفذ الراوتر ($gatewayIp) من داخل المحاكي. ثبّت تطبيق الـ APK على هاتفك المتصل بالواي فاي أو استخدم وضع المحاكاة التجريبي."
                } else {
                    "تعذر الاتصال بالراوتر ($gatewayIp). تأكد من أن منفذ الويب متاح وأن الهاتف متصل بنفس شبكة الواي فاي للراوتر."
                }
            }
            e is java.net.UnknownHostException || msg.contains("No address associated", ignoreCase = true) -> {
                "تعذر العثور على عنوان IP المحدد ($gatewayIp). يرجى التأكد من كتابة العنوان بشكل صحيح."
            }
            else -> {
                msg.ifBlank { "خطأ غير متوقع أثناء الاتصال بالراوتر." }
            }
        }
    }
}
