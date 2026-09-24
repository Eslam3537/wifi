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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class RouterRepositoryImpl(
    private val context: Context
) : RouterRepository {

    private val secureStorage = SecureCredentialsStorage(context)

    // Current active client strictly isolated to router_control operations
    private var activeClient: OkHttpClient = RouterHttpClientFactory.standardHttpClient

    private val _uiState = MutableStateFlow<RouterUiState>(RouterUiState.Idle)
    override val uiState: StateFlow<RouterUiState> = _uiState.asStateFlow()

    private var activeSession: RouterSessionContext? = null
    private var activeStrategy: RouterStrategy? = null
    private var currentStatus: RouterStatusInfo? = null
    private var currentDevices: MutableList<RouterConnectedDevice> = mutableListOf()

    private data class ProtocolProbeResult(
        val protocol: String,
        val client: OkHttpClient,
        val initialHtml: String = "",
        val headers: Map<String, List<String>> = emptyMap(),
        val serverHeader: String = ""
    )

    override fun isConnectedToLocalWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun cleanGatewayIp(rawIp: String): String {
        return rawIp.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
    }

    /**
     * Probes the gateway using HTTP first, then falls back to HTTPS with self-signed SSL support.
     * Respects previously saved protocol if available to avoid redundant probes.
     */
    private suspend fun resolveProtocolAndClient(
        rawGatewayIp: String,
        preferredProtocol: String? = null
    ): Result<ProtocolProbeResult> = withContext(Dispatchers.IO) {
        val cleanIp = cleanGatewayIp(rawGatewayIp)
        if (cleanIp.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("عنوان IP الخاص بالراوتر فارغ."))
        }

        val trimmedRaw = rawGatewayIp.trim().lowercase()
        val explicitProtocol = when {
            trimmedRaw.startsWith("https://") -> "https"
            trimmedRaw.startsWith("http://") -> "http"
            else -> null
        }

        // Determine protocol probe order
        val effectivePreferred = explicitProtocol ?: preferredProtocol ?: "http"
        val protocolsToTry = if (effectivePreferred.equals("https", ignoreCase = true)) {
            listOf("https", "http")
        } else {
            listOf("http", "https")
        }

        var lastHttpException: Throwable? = null
        var lastHttpsException: Throwable? = null
        var detectedSslIssue = false

        for (proto in protocolsToTry) {
            if (proto == "http") {
                try {
                    val req = Request.Builder()
                        .url("http://$cleanIp/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                    val resp = RouterHttpClientFactory.standardHttpClient.newCall(req).execute()
                    val html = resp.body?.string() ?: ""
                    val headers = resp.headers.toMultimap()
                    val server = resp.header("Server") ?: ""

                    // Connection succeeded on HTTP!
                    return@withContext Result.success(
                        ProtocolProbeResult(
                            protocol = "http",
                            client = RouterHttpClientFactory.standardHttpClient,
                            initialHtml = html,
                            headers = headers,
                            serverHeader = server
                        )
                    )
                } catch (e: Exception) {
                    lastHttpException = e
                }
            } else if (proto == "https") {
                // 1. Try standard CA-validated HTTPS first
                try {
                    val req = Request.Builder()
                        .url("https://$cleanIp/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                    val resp = RouterHttpClientFactory.standardHttpClient.newCall(req).execute()
                    val html = resp.body?.string() ?: ""
                    val headers = resp.headers.toMultimap()
                    val server = resp.header("Server") ?: ""

                    return@withContext Result.success(
                        ProtocolProbeResult(
                            protocol = "https",
                            client = RouterHttpClientFactory.standardHttpClient,
                            initialHtml = html,
                            headers = headers,
                            serverHeader = server
                        )
                    )
                } catch (e: Exception) {
                    lastHttpsException = e

                    // 2. If standard HTTPS threw SSL / CertPath / Handshake error, router has self-signed cert!
                    val isSslError = e is SSLException ||
                            e is SSLHandshakeException ||
                            e is CertPathValidatorException ||
                            (e.cause is CertPathValidatorException) ||
                            (e.message?.contains("CertPathValidatorException", ignoreCase = true) == true) ||
                            (e.message?.contains("Trust anchor", ignoreCase = true) == true)

                    if (isSslError || e is IOException) {
                        detectedSslIssue = true
                        try {
                            // Use isolated LAN self-signed router client
                            val routerSslClient = RouterHttpClientFactory.createRouterSslClient(cleanIp)
                            val req = Request.Builder()
                                .url("https://$cleanIp/")
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build()

                            val resp = routerSslClient.newCall(req).execute()
                            val html = resp.body?.string() ?: ""
                            val headers = resp.headers.toMultimap()
                            val server = resp.header("Server") ?: ""

                            return@withContext Result.success(
                                ProtocolProbeResult(
                                    protocol = "https",
                                    client = routerSslClient,
                                    initialHtml = html,
                                    headers = headers,
                                    serverHeader = server
                                )
                            )
                        } catch (sslFallbackEx: Exception) {
                            lastHttpsException = sslFallbackEx
                        }
                    }
                }
            }
        }

        // Both HTTP and HTTPS failed: Analyze failure to give specific human message
        val isUnreachable = (lastHttpException is ConnectException || lastHttpException is SocketTimeoutException ||
                lastHttpException is UnknownHostException || lastHttpException is NoRouteToHostException) &&
                (lastHttpsException is ConnectException || lastHttpsException is SocketTimeoutException ||
                        lastHttpsException is UnknownHostException || lastHttpsException is NoRouteToHostException)

        val failureMessage = when {
            isUnreachable ->
                "لا توجد استجابة من الراوتر على العنوان ($cleanIp). يرجى التأكد من صحة عنوان IP والاتصال بشبكة الواي فاي للراوتر."

            detectedSslIssue && lastHttpsException != null ->
                "فشل الاتصال المشفر بالراوتر عبر HTTPS (${lastHttpsException.localizedMessage}). يرجى التأكد من إعدادات الراوتر."

            else ->
                "تعذر الاتصال بصفحة الراوتر: ${lastHttpException?.localizedMessage ?: lastHttpsException?.localizedMessage ?: "تحقق من اتصال الشبكة"}"
        }

        Result.failure(IOException(failureMessage))
    }

    override suspend fun detectRouter(gatewayIp: String): Result<RouterVendor> = withContext(Dispatchers.IO) {
        if (!isConnectedToLocalWifi()) {
            val err = "يجب الاتصال بشبكة الواي فاي للراوتر أولاً للوصول إلى لوحة التحكم."
            _uiState.value = RouterUiState.Error(err, isWifiDisconnected = true)
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val cleanIp = cleanGatewayIp(gatewayIp)
            val savedCreds = secureStorage.getCredentials()
            val probeResult = resolveProtocolAndClient(cleanIp, savedCreds?.protocol).getOrThrow()

            activeClient = probeResult.client
            val strategy = RouterStrategyRegistry.findStrategy(
                probeResult.initialHtml,
                probeResult.headers,
                probeResult.serverHeader
            )
            activeStrategy = strategy

            Result.success(strategy.vendor)
        } catch (e: Exception) {
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

        _uiState.value = RouterUiState.Loading("جاري فحص الاتصال بالراوتر (HTTP / HTTPS)…")

        try {
            val cleanIp = cleanGatewayIp(credentials.gatewayIp)
            val saved = secureStorage.getCredentials()
            val preferredProtocol = if (credentials.protocol.isNotBlank() && credentials.protocol != "auto") {
                credentials.protocol
            } else {
                saved?.protocol
            }

            // 1. Auto-detect working protocol and appropriate OkHttpClient
            val probeResolution = resolveProtocolAndClient(cleanIp, preferredProtocol)
            if (probeResolution.isFailure) {
                val err = probeResolution.exceptionOrNull()?.message
                    ?: "تعذر الوصول إلى صفحة الراوتر. تحقق من عنوان IP واتصال الواي فاي."
                _uiState.value = RouterUiState.Error(err)
                return@withContext Result.failure(IOException(err))
            }

            val resolution = probeResolution.getOrThrow()
            activeClient = resolution.client
            val workingProtocol = resolution.protocol

            _uiState.value = RouterUiState.Loading("تم تأمين الاتصال عبر ($workingProtocol). جاري تسجيل الدخول…")

            // 2. Resolve Strategy
            var strategy = activeStrategy
            if (strategy == null) {
                strategy = RouterStrategyRegistry.findStrategy(
                    resolution.initialHtml,
                    resolution.headers,
                    resolution.serverHeader
                )
                activeStrategy = strategy
            }

            // 3. Perform authentication with the resolved protocol
            val effectiveCredentials = credentials.copy(
                gatewayIp = cleanIp,
                protocol = workingProtocol
            )

            val loginResult = strategy.login(activeClient, cleanIp, effectiveCredentials)
            if (loginResult.isFailure) {
                val ex = loginResult.exceptionOrNull()
                val exMsg = ex?.message ?: ""
                val isAuthFailure = exMsg.contains("credentials", ignoreCase = true) ||
                        exMsg.contains("password", ignoreCase = true) ||
                        exMsg.contains("401") || exMsg.contains("403")

                val err = if (isAuthFailure) {
                    "تم الاتصال بصفحة الراوتر ($workingProtocol) بنجاح، ولكن فشل تسجيل الدخول: اسم المستخدم أو كلمة المرور غير صحيحة."
                } else {
                    "فشل تسجيل الدخول: ${ex?.localizedMessage ?: "تحقق من بيانات الدخول"}"
                }

                _uiState.value = RouterUiState.Error(err)
                return@withContext Result.failure(ex ?: IOException(err))
            }

            val session = loginResult.getOrThrow()
            activeSession = session

            // 4. Save working credentials and protocol permanently if remember enabled
            if (credentials.remember) {
                secureStorage.saveCredentials(effectiveCredentials)
            } else {
                secureStorage.clearCredentials()
            }
            secureStorage.saveWorkingProtocol(workingProtocol)

            // 5. Fetch initial status and connected clients
            val statusResult = strategy.fetchStatus(activeClient, session)
            val status = statusResult.getOrElse {
                RouterStatusInfo(
                    isConnected = true,
                    vendor = strategy.vendor,
                    modelName = "${strategy.vendor.displayName} Gateway",
                    gatewayIp = cleanIp,
                    wifiSsid = "Connected Wi-Fi",
                    wifiEnabled = true,
                    connectedDevicesCount = 0,
                    protocol = workingProtocol
                )
            }.copy(protocol = workingProtocol, gatewayIp = cleanIp)

            val devicesResult = strategy.fetchConnectedDevices(activeClient, session)
            val devices = devicesResult.getOrDefault(emptyList()).toMutableList()

            val finalStatus = status.copy(
                connectedDevicesCount = if (devices.isNotEmpty()) devices.size else status.connectedDevicesCount
            )

            currentStatus = finalStatus
            currentDevices = devices

            _uiState.value = RouterUiState.Connected(
                status = finalStatus,
                devices = devices,
                isPerformingAction = false,
                actionFeedback = "تم الاتصال بنجاح عبر $workingProtocol"
            )

            Result.success(finalStatus)
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "تعذر الاتصال بصفحة الراوتر. تحقق من اتصال الواي فاي وعنوان IP."
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
            val statusRes = strategy.fetchStatus(activeClient, session)
            val devicesRes = strategy.fetchConnectedDevices(activeClient, session)

            val newStatus = statusRes.getOrElse {
                currentStatus ?: RouterStatusInfo(
                    isConnected = true,
                    vendor = strategy.vendor,
                    modelName = strategy.vendor.displayName,
                    gatewayIp = session.gatewayIp,
                    wifiSsid = "Home Wi-Fi",
                    wifiEnabled = true,
                    connectedDevicesCount = 0,
                    protocol = session.protocol
                )
            }.copy(protocol = session.protocol, gatewayIp = session.gatewayIp)

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

        val res = strategy.restartRouter(activeClient, session)
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

        val res = strategy.changeWifiPassword(activeClient, session, newPassword, newSsid)
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

        val res = strategy.setDeviceBlocked(activeClient, session, device.mac, newBlockedState)
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
            strategy.logout(activeClient, session)
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
