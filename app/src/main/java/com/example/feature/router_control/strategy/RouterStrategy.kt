package com.example.feature.router_control.strategy

import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterVendor
import okhttp3.OkHttpClient

/**
 * Strategy interface for interacting with different router hardware models & firmware.
 * Each strategy handles:
 *  - Vendor detection via initial HTML & HTTP headers
 *  - Authentication & session cookie preservation
 *  - HTML parsing (Jsoup) for connected devices & Wi-Fi status
 *  - Control commands (Reboot, Wi-Fi password change, Device block/unblock)
 */
interface RouterStrategy {
    val vendor: RouterVendor

    /**
     * Determines whether this strategy matches the router's web portal.
     */
    fun matches(html: String, headers: Map<String, List<String>>, serverHeader: String): Boolean

    /**
     * Logs into the router's web interface and returns a valid session context.
     */
    suspend fun login(client: OkHttpClient, gatewayIp: String, credentials: RouterCredentials): Result<RouterSessionContext>

    /**
     * Parses the router's status page (SSID, Wi-Fi status, model name).
     */
    suspend fun fetchStatus(client: OkHttpClient, session: RouterSessionContext): Result<RouterStatusInfo>

    /**
     * Parses the DHCP client table or ARP/WLAN client list using Jsoup.
     */
    suspend fun fetchConnectedDevices(client: OkHttpClient, session: RouterSessionContext): Result<List<RouterConnectedDevice>>

    /**
     * Updates the Wi-Fi WPA2 pre-shared key (password) and optionally the SSID.
     */
    suspend fun changeWifiPassword(
        client: OkHttpClient,
        session: RouterSessionContext,
        newPassword: String,
        newSsid: String? = null
    ): Result<Boolean>

    /**
     * Blocks or unblocks a device by MAC address (MAC filter / Access Control).
     */
    suspend fun setDeviceBlocked(
        client: OkHttpClient,
        session: RouterSessionContext,
        mac: String,
        blocked: Boolean
    ): Result<Boolean>

    /**
     * Reboots the router device via HTTP POST/GET command.
     */
    suspend fun restartRouter(client: OkHttpClient, session: RouterSessionContext): Result<Boolean>

    /**
     * Ends the active administration session.
     */
    suspend fun logout(client: OkHttpClient, session: RouterSessionContext): Result<Unit>
}
