package com.example.domain.router.discovery

import android.content.Context
import com.example.domain.discovery.NetworkDiscoveryEngine
import com.example.domain.router.model.DetectedRouter
import com.example.domain.router.model.RouterManufacturer
import com.example.domain.router.session.RouterSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class RouterDetector(
    private val context: Context,
    private val discoveryEngine: NetworkDiscoveryEngine = NetworkDiscoveryEngine(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    /**
     * Discovers the router on the active local network non-intrusively.
     * 1. Extracts real default gateway IP from active network link properties.
     * 2. Probes the gateway web admin interface for manufacturer/model signatures.
     */
    suspend fun detectRouter(overrideGatewayIp: String? = null): Result<DetectedRouter> = withContext(Dispatchers.IO) {
        val activeInterface = discoveryEngine.getActiveInterfaceInfo()
        val targetIp = overrideGatewayIp?.takeIf { it.isNotBlank() }
            ?: activeInterface?.gatewayIp
            ?: return@withContext Result.failure(IllegalStateException("No active local Wi-Fi or LAN connection detected."))

        if (!RouterSessionManager.isLocalLanIp(targetIp)) {
            return@withContext Result.failure(IllegalArgumentException("Target IP ($targetIp) is not a private local network address."))
        }

        try {
            val detected = probeGateway(targetIp)
            Result.success(detected)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun probeGateway(gatewayIp: String): DetectedRouter {
        val signatures = mutableListOf<String>()
        var htmlBody = ""
        var serverHeader = ""
        var title: String? = null
        var firmwareVersion: String? = null
        var hardwareVersion: String? = null
        var matchedManufacturer = RouterManufacturer.UNKNOWN
        var matchedModel = "Unknown Router"
        var isSupported = false

        // Endpoints to probe in priority order
        val probeEndpoints = listOf(
            "http://$gatewayIp/",
            "http://$gatewayIp/html/index.html",
            "http://$gatewayIp/login.html"
        )

        for (endpoint in probeEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; NetManagerPro)")
                    .build()

                val res = httpClient.newCall(req).execute()
                serverHeader = res.header("Server") ?: serverHeader
                val body = res.body?.string() ?: ""

                if (body.isNotBlank()) {
                    htmlBody = body
                    val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE).find(body)
                    if (titleMatch != null) {
                        title = titleMatch.groupValues[1].trim()
                    }
                    break
                }
            } catch (_: Exception) {}
        }

        if (serverHeader.isNotBlank()) {
            signatures.add("Server: $serverHeader")
        }
        if (!title.isNullOrBlank()) {
            signatures.add("Title: $title")
        }

        // ==========================================
        // 1. Check Huawei HG630 V2 / Home Gateway Signatures
        // ==========================================
        val isHuawei = serverHeader.contains("HuaweiHomeGateway", ignoreCase = true) ||
                htmlBody.contains("HuaweiHomeGateway", ignoreCase = true) ||
                htmlBody.contains("Huawei Technologies", ignoreCase = true) ||
                htmlBody.contains("HG630", ignoreCase = true) ||
                htmlBody.contains("HG658", ignoreCase = true) ||
                htmlBody.contains("EchoLife", ignoreCase = true) ||
                htmlBody.contains("api/system/user_login", ignoreCase = true) ||
                htmlBody.contains("index/login.cgi", ignoreCase = true) ||
                (title?.contains("Huawei", ignoreCase = true) == true) ||
                (title?.contains("HG630", ignoreCase = true) == true)

        if (isHuawei) {
            matchedManufacturer = RouterManufacturer.HUAWEI
            isSupported = true

            // Refine model
            matchedModel = when {
                htmlBody.contains("HG630 V2", ignoreCase = true) || title?.contains("HG630 V2", ignoreCase = true) == true -> "Huawei HG630 V2"
                htmlBody.contains("HG630", ignoreCase = true) || title?.contains("HG630", ignoreCase = true) == true -> "Huawei HG630 V2"
                htmlBody.contains("HG658", ignoreCase = true) || title?.contains("HG658", ignoreCase = true) == true -> "Huawei HG658 V2"
                htmlBody.contains("EchoLife", ignoreCase = true) || title?.contains("EchoLife", ignoreCase = true) == true -> "Huawei EchoLife Series"
                else -> "Huawei HG630 V2 (Home Gateway)"
            }

            // Try fast probe for firmware version if unauthenticated deviceinfo endpoint responds
            try {
                val fwReq = Request.Builder()
                    .url("http://$gatewayIp/api/system/deviceinfo")
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; NetManagerPro)")
                    .build()
                val fwRes = httpClient.newCall(fwReq).execute()
                if (fwRes.isSuccessful) {
                    val fwBody = fwRes.body?.string() ?: ""
                    val fwMatch = Regex(""""SoftwareVersion"\s*:\s*"([^"]+)"""").find(fwBody)
                    val hwMatch = Regex(""""HardwareVersion"\s*:\s*"([^"]+)"""").find(fwBody)
                    if (fwMatch != null) firmwareVersion = fwMatch.groupValues[1]
                    if (hwMatch != null) hardwareVersion = hwMatch.groupValues[1]
                }
            } catch (_: Exception) {}

            return DetectedRouter(
                gatewayIp = gatewayIp,
                manufacturer = matchedManufacturer,
                modelName = matchedModel,
                firmwareVersion = firmwareVersion,
                hardwareVersion = hardwareVersion,
                webAdminTitle = title ?: "Huawei Home Gateway",
                isSupported = true,
                detectedFeatures = listOf("WEB_ADMIN_LOGIN", "LIVE_TELEMETRY", "WIFI_CONFIG", "CONNECTED_HOSTS", "REBOOT"),
                rawSignatures = signatures
            )
        }

        // ==========================================
        // 2. Check TP-Link Signatures
        // ==========================================
        val isTpLink = htmlBody.contains("TP-LINK", ignoreCase = true) ||
                htmlBody.contains("Archer", ignoreCase = true) ||
                serverHeader.contains("TP-LINK", ignoreCase = true) ||
                (title?.contains("TP-LINK", ignoreCase = true) == true)

        if (isTpLink) {
            matchedManufacturer = RouterManufacturer.TPLINK
            matchedModel = if (htmlBody.contains("Archer", ignoreCase = true)) "TP-Link Archer Series" else "TP-Link Router"
            return DetectedRouter(
                gatewayIp = gatewayIp,
                manufacturer = matchedManufacturer,
                modelName = matchedModel,
                webAdminTitle = title,
                isSupported = false,
                detectedFeatures = listOf("WEB_ADMIN"),
                rawSignatures = signatures
            )
        }

        // ==========================================
        // 3. Check ZTE Signatures
        // ==========================================
        val isZte = htmlBody.contains("ZTE", ignoreCase = true) ||
                htmlBody.contains("ZXHN", ignoreCase = true) ||
                serverHeader.contains("ZTE", ignoreCase = true) ||
                (title?.contains("ZTE", ignoreCase = true) == true)

        if (isZte) {
            matchedManufacturer = RouterManufacturer.ZTE
            matchedModel = "ZTE ZXHN Series"
            return DetectedRouter(
                gatewayIp = gatewayIp,
                manufacturer = matchedManufacturer,
                modelName = matchedModel,
                webAdminTitle = title,
                isSupported = false,
                detectedFeatures = listOf("WEB_ADMIN"),
                rawSignatures = signatures
            )
        }

        // ==========================================
        // 4. Check D-Link Signatures
        // ==========================================
        val isDLink = htmlBody.contains("D-Link", ignoreCase = true) ||
                htmlBody.contains("DIR-", ignoreCase = true) ||
                (title?.contains("D-Link", ignoreCase = true) == true)

        if (isDLink) {
            matchedManufacturer = RouterManufacturer.DLINK
            matchedModel = "D-Link Router"
            return DetectedRouter(
                gatewayIp = gatewayIp,
                manufacturer = matchedManufacturer,
                modelName = matchedModel,
                webAdminTitle = title,
                isSupported = false,
                detectedFeatures = listOf("WEB_ADMIN"),
                rawSignatures = signatures
            )
        }

        // Generic / Unknown Router
        return DetectedRouter(
            gatewayIp = gatewayIp,
            manufacturer = RouterManufacturer.GENERIC,
            modelName = if (!title.isNullOrBlank()) title else if (serverHeader.isNotBlank()) "Gateway ($serverHeader)" else "Generic Gateway",
            webAdminTitle = title,
            isSupported = false,
            detectedFeatures = emptyList(),
            rawSignatures = signatures
        )
    }
}
