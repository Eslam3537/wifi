package com.example.data.repository

import android.content.Context
import com.example.domain.router.RouterSettingsManager
import com.example.domain.router.client.RouterClient
import com.example.domain.router.client.RouterClientFactory
import com.example.domain.router.discovery.RouterDetector
import com.example.domain.router.model.*
import com.example.domain.router.session.RouterSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class RouterRepository(
    private val context: Context? = null
) {

    private val detector: RouterDetector? = context?.let { RouterDetector(it) }
    private val settingsManager: RouterSettingsManager? = context?.let { RouterSettingsManager(it) }

    private val _discoveryState = MutableStateFlow<RouterDiscoveryState>(RouterDiscoveryState.Idle)
    val discoveryState: StateFlow<RouterDiscoveryState> = _discoveryState.asStateFlow()

    private val _detectedRouter = MutableStateFlow<DetectedRouter?>(null)
    val detectedRouter: StateFlow<DetectedRouter?> = _detectedRouter.asStateFlow()

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

    // Active client instance based on detection
    private var activeClient: RouterClient = RouterClientFactory.getHuaweiClient()
    private var activeCredentials: RouterCredentials? = null

    /**
     * Non-intrusive auto-discovery of the local default gateway router.
     */
    suspend fun discoverRouter(
        overrideGatewayIp: String? = null,
        autoLoginIfSaved: Boolean = true
    ): Result<DetectedRouter> = withContext(Dispatchers.IO) {
        val det = detector ?: return@withContext Result.failure(IllegalStateException("Detector not initialized."))

        _discoveryState.value = RouterDiscoveryState.Detecting(overrideGatewayIp)
        _isBusy.value = true
        _lastErrorMessage.value = null

        val result = det.detectRouter(overrideGatewayIp)

        if (result.isSuccess) {
            val router = result.getOrThrow()
            _detectedRouter.value = router
            _discoveryState.value = RouterDiscoveryState.Detected(router)
            activeClient = RouterClientFactory.createClient(router)
            _isBusy.value = false

            // If credentials are saved for this gateway, attempt auto-connection
            if (autoLoginIfSaved && settingsManager != null) {
                val savedCreds = settingsManager.getRouterCredentials()
                val savedIp = settingsManager.getSavedGatewayIp()
                if (savedCreds != null && (savedIp == null || savedIp == router.gatewayIp)) {
                    val creds = RouterCredentials(
                        username = savedCreds.first,
                        password = savedCreds.second,
                        gatewayIp = router.gatewayIp
                    )
                    connect(creds)
                }
            }

            Result.success(router)
        } else {
            val error = result.exceptionOrNull()?.localizedMessage ?: "Failed to detect router on local network."
            _discoveryState.value = RouterDiscoveryState.NotDetected(overrideGatewayIp, error)
            _isBusy.value = false
            Result.failure(result.exceptionOrNull() ?: Exception(error))
        }
    }

    /**
     * Authenticates with the real router and loads live telemetry.
     */
    suspend fun connect(credentials: RouterCredentials): Result<RouterDashboardData> = withContext(Dispatchers.IO) {
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
            _connectionState.value = RouterConnectionState.AUTHENTICATING
            activeCredentials = credentials

            val loginResult = activeClient.login(credentials.gatewayIp, credentials)
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

            // Fetch 100% live dashboard data directly from the router hardware
            val dataResult = activeClient.getDashboardData(session)
            val liveData = dataResult.getOrDefault(
                RouterDashboardData(
                    deviceInfo = RouterDeviceInfo(model = _detectedRouter.value?.modelName ?: "Huawei HG630 V2")
                )
            )
            _dashboardData.value = liveData
            _isBusy.value = false

            Result.success(liveData)
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
            val res = activeClient.getDashboardData(session)
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
        val session = _currentSession.value
            ?: return@withContext Result.failure(IllegalStateException("No active router session."))

        _isBusy.value = true
        try {
            val result = activeClient.updateWifiSettings(session, payload)
            if (result.isSuccess) {
                // Refresh dashboard to verify and reflect real hardware state
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
        val session = _currentSession.value
            ?: return@withContext Result.failure(IllegalStateException("No active router session."))

        _isBusy.value = true
        try {
            val result = activeClient.reboot(session)
            disconnect()
            _isBusy.value = false
            result
        } catch (e: Exception) {
            _isBusy.value = false
            Result.failure(e)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val session = _currentSession.value
        if (session != null) {
            try {
                activeClient.logout(session)
            } catch (_: Exception) {}
        }
        _currentSession.value = null
        _connectionState.value = RouterConnectionState.DISCONNECTED
        _dashboardData.value = null
        _isBusy.value = false
        activeCredentials = null
    }

    fun resetDiscovery() {
        _discoveryState.value = RouterDiscoveryState.Idle
        _detectedRouter.value = null
    }
}
