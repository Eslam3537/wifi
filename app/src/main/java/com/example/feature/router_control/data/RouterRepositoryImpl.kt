package com.example.feature.router_control.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.feature.router_control.model.RouterConnectedDevice
import com.example.feature.router_control.model.RouterCredentials
import com.example.feature.router_control.model.RouterStatusInfo
import com.example.feature.router_control.model.RouterUiState
import com.example.feature.router_control.model.RouterVendor
import com.example.feature.router_control.security.SecureCredentialsStorage
import com.example.feature.router_control.strategy.RouterSessionContext
import com.example.feature.router_control.strategy.RouterStrategy
import com.example.feature.router_control.strategy.RouterStrategyRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class RouterRepositoryImpl(
    private val context: Context
) : RouterRepository {

    private val secureStorage = SecureCredentialsStorage(context)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _uiState = MutableStateFlow<RouterUiState>(RouterUiState.Idle)
    override val uiState: StateFlow<RouterUiState> = _uiState.asStateFlow()

    private var activeSession: RouterSessionContext? = null
    private var activeStrategy: RouterStrategy? = null
    private var currentStatus: RouterStatusInfo? = null
    private var currentDevices: MutableList<RouterConnectedDevice> = mutableListOf()

    override fun isConnectedToLocalWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override suspend fun detectRouter(gatewayIp: String): Result<RouterVendor> = withContext(Dispatchers.IO) {
        if (!isConnectedToLocalWifi()) {
            val err = "يجب الاتصال بشبكة الواي فاي للراوتر أولاً للوصول إلى لوحة التحكم."
            _uiState.value = RouterUiState.Error(err, isWifiDisconnected = true)
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val probeUrl = "http://$gatewayIp/"
            val req = Request.Builder()
                .url(probeUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val resp = httpClient.newCall(req).execute()
            val serverHeader = resp.header("Server") ?: ""
            val html = resp.body?.string() ?: ""

            val strategy = RouterStrategyRegistry.findStrategy(html, resp.headers.toMultimap(), serverHeader)
            activeStrategy = strategy

            Result.success(strategy.vendor)
        } catch (e: Exception) {
            // Even if direct probe times out, fallback to generic
            activeStrategy = RouterStrategyRegistry.getStrategyByVendor(RouterVendor.GENERIC)
            Result.success(RouterVendor.GENERIC)
        }
    }

    override suspend fun login(credentials: RouterCredentials): Result<RouterStatusInfo> = withContext(Dispatchers.IO) {
        if (!isConnectedToLocalWifi()) {
            val err = "الجهاز غير متصل بشبكة الواي فاي المحلية. يُرجى الاتصال بالواي فاي والمحاولة مجدداً."
            _uiState.value = RouterUiState.Error(err, isWifiDisconnected = true)
            return@withContext Result.failure(IllegalStateException(err))
        }

        _uiState.value = RouterUiState.Loading("جاري الاتصال والتحقق من بيانات الراوتر…")

        try {
            // 1. Detect or use existing strategy
            var strategy = activeStrategy
            if (strategy == null) {
                detectRouter(credentials.gatewayIp)
                strategy = activeStrategy ?: RouterStrategyRegistry.getStrategyByVendor(RouterVendor.GENERIC)
            }

            // 2. Perform authentication
            val loginResult = strategy.login(httpClient, credentials.gatewayIp, credentials)
            if (loginResult.isFailure) {
                val ex = loginResult.exceptionOrNull()
                val err = "فشل تسجيل الدخول: تحقق من عنوان IP واسم المستخدم وكلمة المرور (${ex?.message ?: "خطأ غير معروف"})"
                _uiState.value = RouterUiState.Error(err)
                return@withContext Result.failure(ex ?: IOException(err))
            }

            val session = loginResult.getOrThrow()
            activeSession = session

            // 3. Save credentials if remember enabled
            if (credentials.remember) {
                secureStorage.saveCredentials(credentials)
            } else {
                secureStorage.clearCredentials()
            }

            // 4. Fetch initial status and devices
            val statusResult = strategy.fetchStatus(httpClient, session)
            val status = statusResult.getOrElse {
                RouterStatusInfo(
                    isConnected = true,
                    vendor = strategy.vendor,
                    modelName = "${strategy.vendor.displayName} Gateway",
                    gatewayIp = credentials.gatewayIp,
                    wifiSsid = "Connected Wi-Fi",
                    wifiEnabled = true,
                    connectedDevicesCount = 0
                )
            }

            val devicesResult = strategy.fetchConnectedDevices(httpClient, session)
            val devices = devicesResult.getOrDefault(emptyList()).toMutableList()

            val finalStatus = status.copy(
                connectedDevicesCount = if (devices.isNotEmpty()) devices.size else status.connectedDevicesCount
            )

            currentStatus = finalStatus
            currentDevices = devices

            _uiState.value = RouterUiState.Connected(
                status = finalStatus,
                devices = devices
            )

            Result.success(finalStatus)
        } catch (e: Exception) {
            val errMsg = "تعذر الاتصال بصفحة الراوتر: ${e.localizedMessage ?: "تحقق من اتصال الواي فاي وعنوان IP"}"
            _uiState.value = RouterUiState.Error(errMsg)
            Result.failure(e)
        }
    }

    override suspend fun refreshData(): Result<Pair<RouterStatusInfo, List<RouterConnectedDevice>>> = withContext(Dispatchers.IO) {
        val session = activeSession
        val strategy = activeStrategy
        if (session == null || strategy == null) {
            val err = "لا توجد جلسة نشطة. يرجى تسجيل الدخول أولاً."
            _uiState.value = RouterUiState.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val currentState = _uiState.value
        if (currentState is RouterUiState.Connected) {
            _uiState.value = currentState.copy(isPerformingAction = true, actionFeedback = "جاري تحديث قائمة الأجهزة…")
        }

        try {
            val statusRes = strategy.fetchStatus(httpClient, session)
            val devicesRes = strategy.fetchConnectedDevices(httpClient, session)

            val newStatus = statusRes.getOrElse { currentStatus ?: RouterStatusInfo(
                isConnected = true,
                vendor = strategy.vendor,
                modelName = strategy.vendor.displayName,
                gatewayIp = session.gatewayIp,
                wifiSsid = "Home Wi-Fi",
                wifiEnabled = true,
                connectedDevicesCount = 0
            ) }

            val newDevices = devicesRes.getOrDefault(currentDevices)
            val updatedStatus = newStatus.copy(connectedDevicesCount = newDevices.size)

            currentStatus = updatedStatus
            currentDevices = newDevices.toMutableList()

            _uiState.value = RouterUiState.Connected(
                status = updatedStatus,
                devices = newDevices,
                isPerformingAction = false,
                actionFeedback = "تم تحديث البيانات بنجاح"
            )

            Result.success(Pair(updatedStatus, newDevices))
        } catch (e: Exception) {
            if (currentState is RouterUiState.Connected) {
                _uiState.value = currentState.copy(isPerformingAction = false, actionFeedback = "فشل التحديث: ${e.message}")
            }
            Result.failure(e)
        }
    }

    override suspend fun restartRouter(): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("No active router session"))
        val strategy = activeStrategy ?: return@withContext Result.failure(IllegalStateException("No strategy found"))

        val currentState = _uiState.value
        if (currentState is RouterUiState.Connected) {
            _uiState.value = currentState.copy(isPerformingAction = true, actionFeedback = "جاري إرسال أمر إعادة تشغيل الراوتر…")
        }

        val res = strategy.restartRouter(httpClient, session)
        if (res.isSuccess) {
            if (currentState is RouterUiState.Connected) {
                _uiState.value = currentState.copy(
                    isPerformingAction = false,
                    actionFeedback = "تم إرسال أمر إعادة التشغيل بنجاح! سيعود الراوتر للعمل خلال دقيقة."
                )
            }
        } else {
            if (currentState is RouterUiState.Connected) {
                _uiState.value = currentState.copy(
                    isPerformingAction = false,
                    actionFeedback = "تعذر إعادة تشغيل الراوتر: ${res.exceptionOrNull()?.message}"
                )
            }
        }
        res
    }

    override suspend fun changeWifiPassword(newPassword: String, newSsid: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("No active router session"))
        val strategy = activeStrategy ?: return@withContext Result.failure(IllegalStateException("No strategy found"))

        val currentState = _uiState.value
        if (currentState is RouterUiState.Connected) {
            _uiState.value = currentState.copy(isPerformingAction = true, actionFeedback = "جاري حفظ باسورد الواي فاي الجديد…")
        }

        val res = strategy.changeWifiPassword(httpClient, session, newPassword, newSsid)
        if (res.isSuccess) {
            if (currentState is RouterUiState.Connected) {
                val updatedStatus = currentState.status.copy(
                    wifiSsid = newSsid ?: currentState.status.wifiSsid
                )
                _uiState.value = currentState.copy(
                    status = updatedStatus,
                    isPerformingAction = false,
                    actionFeedback = "تم تغيير باسورد الواي فاي بنجاح! قد تحتاج لإعادة الاتصال بالشبكة."
                )
            }
        } else {
            if (currentState is RouterUiState.Connected) {
                _uiState.value = currentState.copy(
                    isPerformingAction = false,
                    actionFeedback = "فشل تغيير باسورد الواي فاي: ${res.exceptionOrNull()?.message}"
                )
            }
        }
        res
    }

    override suspend fun toggleDeviceBlock(device: RouterConnectedDevice): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("No active router session"))
        val strategy = activeStrategy ?: return@withContext Result.failure(IllegalStateException("No strategy found"))

        val currentState = _uiState.value
        val newBlockedState = !device.isBlocked
        val actionText = if (newBlockedState) "حظر" else "رفع حظر"

        if (currentState is RouterUiState.Connected) {
            _uiState.value = currentState.copy(isPerformingAction = true, actionFeedback = "جاري $actionText الجهاز ${device.hostname}…")
        }

        val res = strategy.setDeviceBlocked(httpClient, session, device.mac, newBlockedState)
        if (res.isSuccess) {
            val updatedList = currentDevices.map {
                if (it.mac.equals(device.mac, ignoreCase = true)) {
                    it.copy(isBlocked = newBlockedState)
                } else it
            }
            currentDevices = updatedList.toMutableList()

            if (currentState is RouterUiState.Connected) {
                _uiState.value = currentState.copy(
                    devices = updatedList,
                    isPerformingAction = false,
                    actionFeedback = "تم $actionText الجهاز بنجاح"
                )
            }
        } else {
            if (currentState is RouterUiState.Connected) {
                _uiState.value = currentState.copy(
                    isPerformingAction = false,
                    actionFeedback = "تعذر $actionText الجهاز: ${res.exceptionOrNull()?.message}"
                )
            }
        }
        res
    }

    override suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        val session = activeSession
        val strategy = activeStrategy
        if (session != null && strategy != null) {
            strategy.logout(httpClient, session)
        }
        activeSession = null
        currentStatus = null
        currentDevices.clear()
        _uiState.value = RouterUiState.Idle
        Result.success(Unit)
    }

    override fun getSavedCredentials(): RouterCredentials? {
        return secureStorage.getCredentials()
    }

    override fun saveCredentials(credentials: RouterCredentials) {
        secureStorage.saveCredentials(credentials)
    }

    override fun clearCredentials() {
        secureStorage.clearCredentials()
    }
}
