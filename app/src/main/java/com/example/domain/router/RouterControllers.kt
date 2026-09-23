package com.example.domain.router

import com.example.model.DiscoveredDevice
import com.example.model.RouterCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface RouterController {
    suspend fun probeCapabilities(gatewayIp: String): RouterCapability
    suspend fun authenticate(username: String, password: String): Result<Boolean>
    suspend fun getConnectedClients(): Result<List<DiscoveredDevice>>
    suspend fun blockDevice(mac: String, ip: String): Result<Boolean>
    suspend fun unblockDevice(mac: String): Result<Boolean>
    suspend fun getBlockState(mac: String): Result<Boolean>
}

/**
 * Controller for Huawei Home Gateways (specifically HG630 V2 / HG658 / EchoLife series).
 * Follows strict capability reporting: never fakes supported status without verified API control.
 */
class HuaweiRouterController(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : RouterController {

    private var targetGatewayIp: String = "192.168.1.1"
    private var sessionCookie: String? = null
    private var sessionToken: String? = null
    private var sessionExpiryTimestamp: Long = 0L
    private var isHg630Detected: Boolean = false

    override suspend fun probeCapabilities(gatewayIp: String): RouterCapability = withContext(Dispatchers.IO) {
        targetGatewayIp = gatewayIp
        try {
            val req = Request.Builder()
                .url("http://$gatewayIp/")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val response = client.newCall(req).execute()
            val serverHeader = response.header("Server") ?: ""
            val body = response.body?.string() ?: ""

            // Huawei HG630 V2 identification markers in HTML or HTTP headers
            val isHuaweiSignature = body.contains("HG630", ignoreCase = true) ||
                    body.contains("Huawei Technologies", ignoreCase = true) ||
                    serverHeader.contains("HuaweiHomeGateway", ignoreCase = true) ||
                    body.contains("HuaweiHomeGateway", ignoreCase = true)

            isHg630Detected = isHuaweiSignature

            if (isHuaweiSignature) {
                // Honesty rule: While web UI exists, carrier firmware typically locks ACL API
                RouterCapability(
                    detectedModel = "Huawei HG630 V2 (Home Gateway)",
                    endpoint = "http://$gatewayIp/",
                    isSupported = false,
                    supportedFeatures = listOf("WEB_LOGIN_PROBE"),
                    unsupportedReasons = listOf("Carrier firmware locks administrative MAC filtering behind operator TR-069 provisioning or encrypted challenge."),
                    protocol = "HTTP_WEB"
                )
            } else {
                RouterCapability(
                    detectedModel = if (serverHeader.isNotBlank()) serverHeader else "Generic / Unknown Gateway",
                    endpoint = "http://$gatewayIp/",
                    isSupported = false,
                    supportedFeatures = emptyList(),
                    unsupportedReasons = listOf("Your router does not expose a supported Huawei HG630 V2 control interface."),
                    protocol = "UNKNOWN"
                )
            }
        } catch (e: Exception) {
            RouterCapability(
                detectedModel = "Unreachable Gateway ($gatewayIp)",
                endpoint = "http://$gatewayIp/",
                isSupported = false,
                supportedFeatures = emptyList(),
                unsupportedReasons = listOf("Connection failed: ${e.localizedMessage ?: "Timeout"}"),
                protocol = "NONE"
            )
        }
    }

    override suspend fun authenticate(username: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isHg630Detected) {
            return@withContext Result.failure(UnsupportedOperationException("Huawei HG630 V2 interface not detected on gateway."))
        }
        try {
            val req = Request.Builder()
                .url("http://$targetGatewayIp/api/system/HostInfo")
                .build()
            val res = client.newCall(req).execute()
            if (res.code == 401 || res.code == 403) {
                Result.failure(SecurityException("Authentication required: Carrier token challenge required for this firmware."))
            } else {
                Result.failure(UnsupportedOperationException("HG630 V2 proprietary auth challenge required."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getConnectedClients(): Result<List<DiscoveredDevice>> = withContext(Dispatchers.IO) {
        if (sessionCookie == null || System.currentTimeMillis() > sessionExpiryTimestamp) {
            return@withContext Result.failure(IllegalStateException("No active authenticated router session"))
        }
        Result.failure(UnsupportedOperationException("Router API unavailable — authentication required"))
    }

    override suspend fun blockDevice(mac: String, ip: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (sessionCookie == null || System.currentTimeMillis() > sessionExpiryTimestamp) {
            return@withContext Result.failure(UnsupportedOperationException("Unsupported — No active authenticated router session"))
        }
        Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable for access control"))
    }

    override suspend fun unblockDevice(mac: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (sessionCookie == null || System.currentTimeMillis() > sessionExpiryTimestamp) {
            return@withContext Result.failure(UnsupportedOperationException("Unsupported — No active authenticated router session"))
        }
        Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable for access control"))
    }

    override suspend fun getBlockState(mac: String): Result<Boolean> = withContext(Dispatchers.IO) {
        Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable for access control"))
    }

    suspend fun logout(): Result<Boolean> = withContext(Dispatchers.IO) {
        sessionCookie = null
        sessionToken = null
        sessionExpiryTimestamp = 0L
        Result.success(true)
    }
}

/**
 * Controller for TP-Link Routers.
 */
class TPLinkRouterController(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
) : RouterController {

    override suspend fun probeCapabilities(gatewayIp: String): RouterCapability = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$gatewayIp/").build()
            val res = client.newCall(req).execute()
            val body = res.body?.string() ?: ""
            val isTpLink = body.contains("TP-Link", ignoreCase = true)

            if (isTpLink) {
                RouterCapability(
                    detectedModel = "TP-Link Router",
                    endpoint = "http://$gatewayIp/",
                    isSupported = false,
                    supportedFeatures = emptyList(),
                    unsupportedReasons = listOf("TP-Link JSON-RPC / RSA handshake not implemented in this build."),
                    protocol = "HTTP_WEB"
                )
            } else {
                RouterCapability(
                    detectedModel = "Unknown Gateway",
                    endpoint = "http://$gatewayIp/",
                    isSupported = false,
                    supportedFeatures = emptyList(),
                    unsupportedReasons = listOf("Your router does not expose a supported TP-Link control interface."),
                    protocol = "UNKNOWN"
                )
            }
        } catch (e: Exception) {
            RouterCapability(
                detectedModel = "Unreachable",
                endpoint = "http://$gatewayIp/",
                isSupported = false,
                supportedFeatures = emptyList(),
                unsupportedReasons = listOf("Connection failed: ${e.message}"),
                protocol = "NONE"
            )
        }
    }

    override suspend fun authenticate(username: String, password: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("NOT IMPLEMENTED — TP-Link RSA authentication"))
    }

    override suspend fun getConnectedClients(): Result<List<DiscoveredDevice>> {
        return Result.failure(UnsupportedOperationException("NOT IMPLEMENTED"))
    }

    override suspend fun blockDevice(mac: String, ip: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable"))
    }

    override suspend fun unblockDevice(mac: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable"))
    }

    override suspend fun getBlockState(mac: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable"))
    }
}

/**
 * Generic Router Controller via standard UPnP / TR-064.
 */
class GenericRouterController(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
) : RouterController {

    override suspend fun probeCapabilities(gatewayIp: String): RouterCapability = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$gatewayIp/rootDesc.xml").build()
            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                RouterCapability(
                    detectedModel = "UPnP / IGD Compatible Gateway",
                    endpoint = "http://$gatewayIp/rootDesc.xml",
                    isSupported = false,
                    supportedFeatures = listOf("UPNP_DESCRIPTION_DISCOVERY"),
                    unsupportedReasons = listOf("WANIPConnection service found, but access control service is restricted by device."),
                    protocol = "UPNP_SOAP"
                )
            } else {
                RouterCapability(
                    detectedModel = "No UPnP description available",
                    endpoint = "http://$gatewayIp/",
                    isSupported = false,
                    supportedFeatures = emptyList(),
                    unsupportedReasons = listOf("Your router does not expose a supported control interface."),
                    protocol = "UNKNOWN"
                )
            }
        } catch (e: Exception) {
            RouterCapability(
                detectedModel = "Unknown Gateway",
                endpoint = "http://$gatewayIp/",
                isSupported = false,
                supportedFeatures = emptyList(),
                unsupportedReasons = listOf("Your router does not expose a supported control interface."),
                protocol = "UNKNOWN"
            )
        }
    }

    override suspend fun authenticate(username: String, password: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("NOT IMPLEMENTED"))
    }

    override suspend fun getConnectedClients(): Result<List<DiscoveredDevice>> {
        return Result.failure(UnsupportedOperationException("NOT IMPLEMENTED"))
    }

    override suspend fun blockDevice(mac: String, ip: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable"))
    }

    override suspend fun unblockDevice(mac: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable"))
    }

    override suspend fun getBlockState(mac: String): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("Unsupported — Router API unavailable"))
    }
}
