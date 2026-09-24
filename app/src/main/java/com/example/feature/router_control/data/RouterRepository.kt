package com.example.feature.router_control.data

import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterUiState
import com.example.feature.router_control.model.RouterVendor
import kotlinx.coroutines.flow.StateFlow

interface RouterRepository {
    val uiState: StateFlow<RouterUiState>

    /**
     * Checks whether the mobile device is currently connected to a local Wi-Fi network.
     */
    fun isConnectedToLocalWifi(): Boolean

    /**
     * Discovers / detects the router model and vendor from the gateway IP.
     */
    suspend fun detectRouter(gatewayIp: String): Result<RouterVendor>

    /**
     * Performs authentication and initiates the management session.
     */
    suspend fun login(credentials: RouterCredentials): Result<RouterStatusInfo>

    /**
     * Refreshes router status and connected client devices.
     */
    suspend fun refreshData(): Result<Pair<RouterStatusInfo, List<RouterConnectedDevice>>>

    /**
     * Sends a command to reboot / restart the router hardware.
     */
    suspend fun restartRouter(): Result<Boolean>

    /**
     * Updates the Wi-Fi password (WPA2-PSK) and optionally SSID.
     */
    suspend fun changeWifiPassword(newPassword: String, newSsid: String? = null): Result<Boolean>

    /**
     * Toggles block status for a specific connected device via MAC filtering.
     */
    suspend fun toggleDeviceBlock(device: RouterConnectedDevice): Result<Boolean>

    /**
     * Disconnects and ends active administration session.
     */
    suspend fun logout(): Result<Unit>

    /**
     * Retrieves saved credentials from encrypted storage.
     */
    fun getSavedCredentials(): RouterCredentials?

    /**
     * Saves credentials to encrypted storage.
     */
    fun saveCredentials(credentials: RouterCredentials)

    /**
     * Clears credentials from encrypted storage.
     */
    fun clearCredentials()
}
