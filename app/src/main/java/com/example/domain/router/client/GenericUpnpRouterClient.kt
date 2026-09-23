package com.example.domain.router.client

import com.example.domain.router.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Generic UPnP / TR-064 Router client for modular fallback.
 */
class GenericUpnpRouterClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
) : RouterClient {

    override val supportedModelName: String = "Generic UPnP / IGD Router"

    override suspend fun probe(gatewayIp: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$gatewayIp/rootDesc.xml").build()
            val res = client.newCall(req).execute()
            res.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun login(gatewayIp: String, credentials: RouterCredentials): Result<RouterSession> {
        return Result.failure(UnsupportedOperationException("UPnP Basic Auth not configured on gateway."))
    }

    override suspend fun getDashboardData(session: RouterSession): Result<RouterDashboardData> {
        return Result.failure(UnsupportedOperationException("Generic UPnP telemetry requires TR-064 access."))
    }

    override suspend fun getConnectedDevices(session: RouterSession): Result<List<RouterConnectedDevice>> {
        return Result.failure(UnsupportedOperationException("TR-064 HostList service is restricted by device."))
    }

    override suspend fun getWifiSettings(session: RouterSession): Result<RouterWifiInfo> {
        return Result.failure(UnsupportedOperationException("WLANConfiguration service restricted."))
    }

    override suspend fun updateWifiSettings(session: RouterSession, payload: WifiUpdatePayload): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("WLANConfiguration service restricted."))
    }

    override suspend fun reboot(session: RouterSession): Result<Boolean> {
        return Result.failure(UnsupportedOperationException("DeviceReset service restricted."))
    }

    override suspend fun logout(session: RouterSession): Result<Boolean> {
        return Result.success(true)
    }
}
