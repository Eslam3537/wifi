package com.example.data.repository

import com.example.domain.router.client.HuaweiHG630V2Client
import com.example.domain.router.client.RouterClient
import com.example.domain.router.client.RouterClientFactory
import com.example.domain.router.model.*
import com.example.domain.router.session.RouterSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class RouterRepository(
    private val client: RouterClient = RouterClientFactory.getHuaweiClient()
) {

    private val _connectionState = MutableStateFlow(RouterConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RouterConnectionState> = _connectionState.asStateFlow()

    private val _dashboardData = MutableStateFlow<RouterDashboardData?>(null)
    val dashboardData: StateFlow<RouterDashboardData?> = _dashboardData.asStateFlow()

    private val _currentSession = MutableStateFlow<RouterSession?>(null)
    val currentSession: StateFlow<RouterSession?> = _currentSession.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private var activeCredentials: RouterCredentials? = null

    fun connectDemoMode() {
        _isDemoMode.value = true
        _isBusy.value = false
        _lastErrorMessage.value = null
        _connectionState.value = RouterConnectionState.CONNECTED
        _currentSession.value = RouterSession(
            gatewayIp = "192.168.1.1 (Demo)",
            sessionId = "DEMO_SESSION_HG630V2",
            csrfToken = "demo_csrf_token_huawei"
        )
        _dashboardData.value = RouterDashboardData(
            deviceInfo = RouterDeviceInfo(
                model = "Huawei HG630 V2 (Home Gateway)",
                firmwareVersion = "V100R001C192B020",
                hardwareVersion = "VER.A",
                serialNumber = "21530327667SZ4009821",
                uptimeSeconds = 172800L,
                formattedUptime = "2d 0h 0m",
                deviceName = "HG630-HomeGateway"
            ),
            wanInfo = RouterWanInfo(
                status = "Connected",
                connectionType = "PPPoE_Routed",
                ip = "197.35.40.12",
                subnet = "255.255.255.255",
                gateway = "197.35.40.1",
                primaryDns = "163.121.128.134",
                secondaryDns = "163.121.128.135",
                mac = "F8:E7:1E:A1:B2:C3"
            ),
            lanInfo = RouterLanInfo(
                ip = "192.168.1.1",
                subnet = "255.255.255.0",
                dhcpEnabled = true,
                dhcpStart = "192.168.1.2",
                dhcpEnd = "192.168.1.254"
            ),
            wifiInfo = RouterWifiInfo(
                enabled = true,
                ssid = "WE_Home_5GHz",
                channel = "6 (Auto)",
                securityMode = "WPA2-PSK (AES)",
                standard = "802.11b/g/n",
                transmitPower = "100%"
            ),
            connectedDevices = listOf(
                RouterConnectedDevice(
                    name = "Galaxy-S24-Ultra",
                    ip = "192.168.1.5",
                    mac = "94:65:2D:11:22:33",
                    connectionType = "Wi-Fi (2.4 GHz)",
                    isOnline = true,
                    leaseTime = "84200s"
                ),
                RouterConnectedDevice(
                    name = "iPhone-15-Pro",
                    ip = "192.168.1.8",
                    mac = "74:8D:08:44:55:66",
                    connectionType = "Wi-Fi (2.4 GHz)",
                    isOnline = true,
                    leaseTime = "79500s"
                ),
                RouterConnectedDevice(
                    name = "Smart-TV-LivingRoom",
                    ip = "192.168.1.15",
                    mac = "B8:27:EB:77:88:99",
                    connectionType = "Ethernet (LAN)",
                    isOnline = true,
                    leaseTime = "86400s"
                ),
                RouterConnectedDevice(
                    name = "MacBook-Air-M3",
                    ip = "192.168.1.22",
                    mac = "F0:18:98:AA:BB:CC",
                    connectionType = "Wi-Fi (2.4 GHz)",
                    isOnline = true,
                    leaseTime = "65000s"
                )
            ),
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun connect(credentials: RouterCredentials): Result<RouterDashboardData> = withContext(Dispatchers.IO) {
        _isDemoMode.value = false
        _isBusy.value = true
        _lastErrorMessage.value = null
        _connectionState.value = RouterConnectionState.CONNECTING

        if (!RouterSessionManager.isLocalLanIp(credentials.gatewayIp)) {
            val error = "Security Warning: Invalid local network IP address (${credentials.gatewayIp}). Router management is restricted to private LAN."
            _lastErrorMessage.value = error
            _connectionState.value = RouterConnectionState.NETWORK_ERROR
            _isBusy.value = false
            return@withContext Result.failure(IllegalArgumentException(error))
        }

        try {
            // Probing
            _connectionState.value = RouterConnectionState.AUTHENTICATING
            activeCredentials = credentials

            val loginResult = client.login(credentials.gatewayIp, credentials)
            val session = loginResult.getOrNull()

            if (session == null || loginResult.isFailure) {
                val error = loginResult.exceptionOrNull()?.localizedMessage ?: "Router authentication failed."
                _lastErrorMessage.value = error
                _connectionState.value = RouterConnectionState.AUTH_FAILED
                _isBusy.value = false
                return@withContext Result.failure(loginResult.exceptionOrNull() ?: Exception(error))
            }

            _currentSession.value = session
            _connectionState.value = RouterConnectionState.CONNECTED

            // Fetch live dashboard data
            val dataResult = client.getDashboardData(session)
            val data = dataResult.getOrDefault(RouterDashboardData())
            _dashboardData.value = data
            _isBusy.value = false

            Result.success(data)
        } catch (e: Exception) {
            val error = e.localizedMessage ?: "Network error connecting to router."
            _lastErrorMessage.value = error
            _connectionState.value = RouterConnectionState.NETWORK_ERROR
            _isBusy.value = false
            Result.failure(e)
        }
    }

    suspend fun refreshDashboard(): Result<RouterDashboardData> = withContext(Dispatchers.IO) {
        val session = _currentSession.value
        if (session == null || session.isExpired) {
            // Attempt auto re-login if credentials available
            val creds = activeCredentials
            if (creds != null) {
                return@withContext connect(creds)
            } else {
                _connectionState.value = RouterConnectionState.SESSION_EXPIRED
                return@withContext Result.failure(IllegalStateException("Router session expired."))
            }
        }

        _isBusy.value = true
        try {
            val res = client.getDashboardData(session)
            if (res.isSuccess) {
                _dashboardData.value = res.getOrNull()
                _lastErrorMessage.value = null
            } else {
                _lastErrorMessage.value = res.exceptionOrNull()?.localizedMessage
            }
            _isBusy.value = false
            res
        } catch (e: Exception) {
            _isBusy.value = false
            _lastErrorMessage.value = e.localizedMessage
            Result.failure(e)
        }
    }

    suspend fun updateWifi(payload: WifiUpdatePayload): Result<Boolean> = withContext(Dispatchers.IO) {
        if (_isDemoMode.value) {
            val current = _dashboardData.value ?: return@withContext Result.success(true)
            val updatedWifi = current.wifiInfo.copy(
                ssid = payload.ssid,
                enabled = payload.enabled ?: current.wifiInfo.enabled,
                channel = payload.channel ?: current.wifiInfo.channel
            )
            _dashboardData.value = current.copy(wifiInfo = updatedWifi, lastUpdated = System.currentTimeMillis())
            return@withContext Result.success(true)
        }

        val session = _currentSession.value
            ?: return@withContext Result.failure(IllegalStateException("No active router session."))

        _isBusy.value = true
        try {
            val result = client.updateWifiSettings(session, payload)
            if (result.isSuccess) {
                // Refresh dashboard to reflect updated Wi-Fi parameters
                refreshDashboard()
            }
            _isBusy.value = false
            result
        } catch (e: Exception) {
            _isBusy.value = false
            Result.failure(e)
        }
    }

    suspend fun rebootRouter(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (_isDemoMode.value) {
            _isBusy.value = true
            kotlinx.coroutines.delay(1000)
            disconnect()
            _isBusy.value = false
            return@withContext Result.success(true)
        }

        val session = _currentSession.value
            ?: return@withContext Result.failure(IllegalStateException("No active router session."))

        _isBusy.value = true
        try {
            val result = client.reboot(session)
            disconnect()
            _isBusy.value = false
            result
        } catch (e: Exception) {
            _isBusy.value = false
            Result.failure(e)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        _isDemoMode.value = false
        val session = _currentSession.value
        if (session != null) {
            try { client.logout(session) } catch (_: Exception) {}
        }
        activeCredentials = null
        _currentSession.value = null
        _dashboardData.value = null
        _connectionState.value = RouterConnectionState.DISCONNECTED
        _lastErrorMessage.value = null
        _isBusy.value = false
    }
}
