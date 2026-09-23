package com.example.domain.router.client

import com.example.domain.router.model.*

interface RouterClient {
    val supportedModelName: String

    /**
     * Probes if the target gateway endpoint corresponds to this router family.
     */
    suspend fun probe(gatewayIp: String): Boolean

    /**
     * Authenticates with the router and returns an active session.
     */
    suspend fun login(gatewayIp: String, credentials: RouterCredentials): Result<RouterSession>

    /**
     * Retrieves aggregated system, WAN, LAN, and Wi-Fi dashboard info.
     */
    suspend fun getDashboardData(session: RouterSession): Result<RouterDashboardData>

    /**
     * Retrieves list of connected LAN and Wi-Fi devices.
     */
    suspend fun getConnectedDevices(session: RouterSession): Result<List<RouterConnectedDevice>>

    /**
     * Retrieves current Wi-Fi configuration and radio status.
     */
    suspend fun getWifiSettings(session: RouterSession): Result<RouterWifiInfo>

    /**
     * Applies new Wi-Fi configuration (SSID / Password / State).
     */
    suspend fun updateWifiSettings(session: RouterSession, payload: WifiUpdatePayload): Result<Boolean>

    /**
     * Reboots the gateway gracefully.
     */
    suspend fun reboot(session: RouterSession): Result<Boolean>

    /**
     * Invalidates the active session on the gateway.
     */
    suspend fun logout(session: RouterSession): Result<Boolean>
}
