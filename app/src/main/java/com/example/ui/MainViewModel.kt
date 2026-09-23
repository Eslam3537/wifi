package com.example.ui

import android.app.Activity
import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.example.data.local.ActivityLogEntity
import com.example.data.local.AppDatabase
import com.example.data.repository.ActivityLogRepository
import com.example.data.repository.DeviceRepository
import com.example.data.repository.RouterRepository
import com.example.domain.capability.CapabilityEngine
import com.example.domain.capability.FullCapabilityAudit
import com.example.domain.capability.SystemCapabilityEvaluator
import com.example.domain.compatibility.CompatibilityScanner
import com.example.domain.diagnostics.NetworkDiagnosticsEngine
import com.example.domain.discovery.NetworkDiscoveryEngine
import com.example.domain.discovery.NetworkUtils
import com.example.domain.privilege.PermissionManager
import com.example.domain.privilege.PrivilegeManager
import com.example.domain.protection.EnforcementBackend
import com.example.domain.protection.FirewallEnforcementEngine
import com.example.domain.protection.NetworkProtectionService
import com.example.domain.protection.RuleStateManager
import com.example.domain.protection.TrafficControlCapability
import com.example.domain.protection.TrafficControlEngine
import com.example.domain.router.HuaweiRouterController
import com.example.domain.router.RouterSettingsManager
import com.example.domain.router.model.*
import com.example.domain.security.NetworkSecurityMonitor
import com.example.domain.security.ProtectedDevicesManager
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.ThemeManager
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val deviceRepository = DeviceRepository(db.deviceDao())
    val activityLogRepository = ActivityLogRepository(db.activityLogDao())

    private val discoveryEngine = NetworkDiscoveryEngine(application)
    private val diagnosticsEngine = NetworkDiagnosticsEngine()
    private val securityMonitor = NetworkSecurityMonitor()
    private val routerController = HuaweiRouterController()
    val routerSettingsManager = RouterSettingsManager(application)
    val protectedDevicesManager = ProtectedDevicesManager(application)
    val ruleStateManager = RuleStateManager(application)

    // Current online devices - single source of truth for active scan results
    private val _currentOnlineDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _currentOnlineDevices.asStateFlow()
    val allKnownDevices = deviceRepository.getDevicesFlow()

    val logs = activityLogRepository.getLogsFlow()
    val securityAlerts: StateFlow<List<SecurityAlert>> = securityMonitor.alerts
    val activeEnforcedRules = ruleStateManager.activeRules
    val isBackgroundProtectionEnabled = ruleStateManager.isBackgroundProtectionEnabled
    val protectionStatusMessage = ruleStateManager.protectionStatusMessage
    val globalBandwidthLimit = ruleStateManager.globalBandwidthLimit

    private val _capabilityAuditResult = MutableStateFlow<FullCapabilityAudit?>(null)
    val capabilityAuditResult: StateFlow<FullCapabilityAudit?> = _capabilityAuditResult.asStateFlow()

    private val _trafficControlCapability = MutableStateFlow(
        TrafficControlCapability(
            isSupported = false,
            mechanism = "UNCHECKED",
            detectedInterface = null,
            tcBinaryPath = null
        )
    )
    val trafficControlCapability: StateFlow<TrafficControlCapability> = _trafficControlCapability.asStateFlow()

    private val _showSmartExitDialog = MutableStateFlow(false)
    val showSmartExitDialog: StateFlow<Boolean> = _showSmartExitDialog.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _selectedDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val selectedDevice: StateFlow<DiscoveredDevice?> = _selectedDevice.asStateFlow()

    private val _selectedDeviceIps = MutableStateFlow<Set<String>>(emptySet())
    val selectedDeviceIps: StateFlow<Set<String>> = _selectedDeviceIps.asStateFlow()

    private val _diagnostics = MutableStateFlow<NetworkDiagnostics?>(null)
    val diagnostics: StateFlow<NetworkDiagnostics?> = _diagnostics.asStateFlow()

    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    private val _routerCapability = MutableStateFlow<RouterCapability?>(null)
    val routerCapability: StateFlow<RouterCapability?> = _routerCapability.asStateFlow()

    private val _isProbingRouter = MutableStateFlow(false)
    val isProbingRouter: StateFlow<Boolean> = _isProbingRouter.asStateFlow()

    val routerRepository = RouterRepository()
    val routerConnectionState: StateFlow<RouterConnectionState> = routerRepository.connectionState
    val routerDashboardData: StateFlow<RouterDashboardData?> = routerRepository.dashboardData
    val routerCurrentSession: StateFlow<RouterSession?> = routerRepository.currentSession
    val routerLastErrorMessage: StateFlow<String?> = routerRepository.lastErrorMessage
    val isRouterBusy: StateFlow<Boolean> = routerRepository.isBusy
    val isRouterDemoMode: StateFlow<Boolean> = routerRepository.isDemoMode

    private val _arpIntegrityReport = MutableStateFlow<ArpIntegrityReport?>(null)
    val arpIntegrityReport: StateFlow<ArpIntegrityReport?> = _arpIntegrityReport.asStateFlow()

    val arpAnomalyEvents: StateFlow<List<ArpAnomalyEvent>> = securityMonitor.arpAnalyzer.anomalyEvents

    private val _rootStatus = MutableStateFlow(RootStatus.NO_ROOT)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private val _kernelSuStatus = MutableStateFlow(KernelSuStatus.NOT_DETECTED)
    val kernelSuStatus: StateFlow<KernelSuStatus> = _kernelSuStatus.asStateFlow()

    private val _compatibilityAudit = MutableStateFlow<CompatibilityAuditResult?>(null)
    val compatibilityAudit: StateFlow<CompatibilityAuditResult?> = _compatibilityAudit.asStateFlow()

    private val _capabilityReport = MutableStateFlow<SystemCapabilityReport?>(null)
    val capabilityReport: StateFlow<SystemCapabilityReport?> = _capabilityReport.asStateFlow()

    private val _networkInfo = MutableStateFlow<NetworkDiscoveryEngine.NetworkInterfaceInfo?>(null)
    val networkInfo: StateFlow<NetworkDiscoveryEngine.NetworkInterfaceInfo?> = _networkInfo.asStateFlow()

    private val _operationFeedback = MutableStateFlow<String?>(null)
    val operationFeedback: StateFlow<String?> = _operationFeedback.asStateFlow()

    private val _currentLanguage = MutableStateFlow(LanguageManager.getSavedLanguage(application))
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    private val _currentThemeMode = MutableStateFlow(ThemeManager.getSavedThemeMode(application))
    val currentThemeMode: StateFlow<AppThemeMode> = _currentThemeMode.asStateFlow()

    private val _hasAllPermissions = MutableStateFlow(
        PermissionManager.areAllRuntimePermissionsGranted(application)
    )
    val hasAllPermissions: StateFlow<Boolean> = _hasAllPermissions.asStateFlow()

    fun updatePermissionStatus() {
        _hasAllPermissions.value = PermissionManager.areAllRuntimePermissionsGranted(getApplication())
    }

    fun onPermissionsResult(allGranted: Boolean) {
        _hasAllPermissions.value = allGranted
        refreshNetworkInfo()
        reevaluateAllCapabilities(forceRefresh = true)
        viewModelScope.launch(Dispatchers.IO) {
            PrivilegeManager.auditRoot(forceRefresh = true)
            PrivilegeManager.auditKernelSu(forceRefresh = true)
        }
    }

    fun setLanguage(language: AppLanguage) {
        _currentLanguage.value = language
        LanguageManager.saveLanguage(getApplication(), language)
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        _currentThemeMode.value = themeMode
        ThemeManager.saveThemeMode(getApplication(), themeMode)
    }

    fun toggleNextThemeMode() {
        val next = when (_currentThemeMode.value) {
            AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.SYSTEM
        }
        setThemeMode(next)
    }

    private var scanJob: Job? = null

    init {
        refreshNetworkInfo()
        reevaluateAllCapabilities(forceRefresh = false)
        viewModelScope.launch(Dispatchers.IO) {
            ruleStateManager.syncWithDatabase(deviceRepository)
            // Load recently seen devices into active view if recent (seen within 5 minutes)
            val initial = deviceRepository.getAllDevices().filter {
                it.status == DeviceStatus.ONLINE && (System.currentTimeMillis() - it.lastSeen < 300_000)
            }
            if (initial.isNotEmpty()) {
                _currentOnlineDevices.value = initial
            }
        }
    }

    fun refreshNetworkInfo() {
        val info = discoveryEngine.getActiveInterfaceInfo()
        _networkInfo.value = info
    }

    private var hasLoggedInitialCapabilities = false

    fun reevaluateAllCapabilities(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val root = PrivilegeManager.checkRootStatus(forceRefresh)
            val ksu = PrivilegeManager.checkKernelSuStatus(forceRefresh)
            _rootStatus.value = root
            _kernelSuStatus.value = ksu

            val compatResult = CompatibilityScanner.auditDevice(getApplication())
            _compatibilityAudit.value = compatResult

            val audit = CapabilityEngine.auditAllCapabilities(
                context = getApplication(),
                routerCapability = _routerCapability.value,
                networkInfo = _networkInfo.value,
                forceRefresh = forceRefresh
            )
            _capabilityAuditResult.value = audit
            _capabilityReport.value = audit.systemReport

            if (forceRefresh || !hasLoggedInitialCapabilities) {
                hasLoggedInitialCapabilities = true
                for ((_, cap) in audit.capabilities) {
                    if (cap.status == CapabilityState.RESTRICTED || cap.status == CapabilityState.UNSUPPORTED) {
                        val level = if (cap.status == CapabilityState.RESTRICTED) "WARN" else "INFO"
                        activityLogRepository.log(
                            component = "Capability",
                            level = level,
                            operation = "${cap.name} State",
                            result = "${cap.status.name}: ${cap.reason}",
                            errorDetails = "Requirements: ${cap.requirements} | Verification: ${cap.verification}"
                        )
                    }
                }
            }
        }
    }

    fun refreshCapabilities(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val audit = CapabilityEngine.auditAllCapabilities(
                context = getApplication(),
                routerCapability = _routerCapability.value,
                networkInfo = _networkInfo.value,
                forceRefresh = forceRefresh
            )
            _capabilityAuditResult.value = audit
            _capabilityReport.value = audit.systemReport
        }
    }

    fun startScan() {
        if (_isScanning.value) return
        refreshNetworkInfo()
        _isScanning.value = true
        _currentOnlineDevices.value = emptyList() // Clean slate; do not retain stale devices

        viewModelScope.launch(Dispatchers.IO) {
            activityLogRepository.log("Discovery", "INFO", "Scan Started", "Beginning verified layered discovery")

            scanJob = launch {
                val currentNetIp = _networkInfo.value?.ip

                discoveryEngine.startLayeredDiscovery()
                    .catch { e ->
                        activityLogRepository.log("Discovery", "ERROR", "Scan Error", e.message ?: "Unknown")
                        _isScanning.value = false
                    }
                    .collect { progressiveList ->
                        // Apply protection flags and current device identification
                        val mappedList = progressiveList.map { dev ->
                            val isCurrent = (dev.ip == currentNetIp) || dev.isCurrentDevice
                            val isProt = isCurrent || dev.isGateway || protectedDevicesManager.isProtected(dev.ip)
                            dev.copy(
                                isCurrentDevice = isCurrent,
                                isProtected = isProt,
                                status = DeviceStatus.ONLINE
                            )
                        }

                        // Single source of truth updated directly from verified live evidence
                        _currentOnlineDevices.value = mappedList

                        // Run security audit on new state
                        val net = _networkInfo.value
                        if (net != null) {
                            val gw = mappedList.firstOrNull { it.isGateway }
                            securityMonitor.auditNetworkState(
                                currentGatewayIp = net.gatewayIp,
                                currentGatewayMac = gw?.mac,
                                currentDnsServers = net.dnsServers,
                                discoveredDevices = mappedList
                            )
                        }
                    }

                // Discovery cycle completed: reconcile with Room history without corrupting online state
                val verifiedOnline = _currentOnlineDevices.value
                val historicalDb = deviceRepository.getAllDevices()
                val reconciled = NetworkUtils.reconcileScanWithDatabaseHistory(verifiedOnline, historicalDb)
                deviceRepository.saveDevices(reconciled)

                _isScanning.value = false
                activityLogRepository.log("Discovery", "INFO", "Scan Completed", "Verified ${verifiedOnline.size} online devices")
                refreshCapabilities()
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _isScanning.value = false
    }

    fun toggleDeviceSelection(ip: String) {
        val current = _selectedDeviceIps.value
        _selectedDeviceIps.value = if (current.contains(ip)) current - ip else current + ip
    }

    fun selectAllEligibleDevices(allDevices: List<DiscoveredDevice>) {
        val currentIp = _networkInfo.value?.ip
        val eligibleIps = allDevices.filter { dev ->
            !dev.isGateway &&
            !dev.isCurrentDevice &&
            (dev.ip != currentIp) &&
            !dev.isProtected &&
            !protectedDevicesManager.isProtected(dev.ip)
        }.map { it.ip }.toSet()

        _selectedDeviceIps.value = eligibleIps
    }

    fun clearDeviceSelection() {
        _selectedDeviceIps.value = emptySet()
    }

    fun toggleDeviceProtection(device: DiscoveredDevice) {
        val currentIp = _networkInfo.value?.ip
        if (device.isGateway || device.isCurrentDevice || device.ip == currentIp) {
            _operationFeedback.value = getApplication<Application>().getString(R.string.cannot_block_current_device)
            return
        }

        val newProtectedState = !protectedDevicesManager.isProtected(device.ip)
        protectedDevicesManager.setProtected(device.ip, newProtectedState)

        viewModelScope.launch(Dispatchers.IO) {
            val updated = device.copy(isProtected = newProtectedState)
            deviceRepository.saveDevice(updated)
            if (_selectedDevice.value?.ip == device.ip) {
                _selectedDevice.value = updated
            }
            // Remove from selection if marked protected
            if (newProtectedState) {
                _selectedDeviceIps.value = _selectedDeviceIps.value - device.ip
            }
            activityLogRepository.log(
                "Protection",
                "INFO",
                if (newProtectedState) "Device Protected" else "Device Unprotected",
                "IP: ${device.ip}"
            )
        }
    }

    fun runDiagnostics(targetIp: String = "8.8.8.8") {
        if (_isDiagnosing.value) return
        _isDiagnosing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            activityLogRepository.log("Diagnostics", "INFO", "Running Diagnostics", "Target: $targetIp")
            val result = diagnosticsEngine.runFullDiagnostics(targetIp)
            _diagnostics.value = result
            _isDiagnosing.value = false
            activityLogRepository.log(
                "Diagnostics",
                "INFO",
                "Diagnostics Result",
                "Ping: ${result.pingMs}ms, Jitter: ${result.jitterMs}ms, Loss: ${result.packetLossPercent}%"
            )
        }
    }

    fun probeRouter(gatewayIp: String) {
        _isProbingRouter.value = true
        viewModelScope.launch(Dispatchers.IO) {
            activityLogRepository.log("Router", "INFO", "Probing Gateway", "IP: $gatewayIp")
            val cap = routerController.probeCapabilities(gatewayIp)
            _routerCapability.value = cap
            _isProbingRouter.value = false
            activityLogRepository.log(
                component = "Router",
                level = if (cap.isSupported) "INFO" else "WARN",
                operation = "Capability Probe",
                result = "Model: ${cap.detectedModel}, Supported: ${cap.isSupported}",
                errorDetails = if (!cap.isSupported) cap.unsupportedReasons.joinToString("; ").ifBlank { "Gateway control interface not recognized or supported" } else null
            )
            refreshCapabilities()
        }
    }

    fun connectRouter(gatewayIp: String, username: String, password: String, saveCredentials: Boolean) {
        viewModelScope.launch {
            if (saveCredentials) {
                routerSettingsManager.saveRouterCredentials(username, password, gatewayIp)
            }
            activityLogRepository.log(
                component = "Router",
                level = "INFO",
                operation = "Router Connect",
                result = "Connecting to Huawei HG630 V2 at $gatewayIp as user '$username'"
            )
            val result = routerRepository.connect(
                RouterCredentials(
                    username = username,
                    password = password,
                    gatewayIp = gatewayIp
                )
            )
            if (result.isSuccess) {
                activityLogRepository.log(
                    component = "Router",
                    level = "INFO",
                    operation = "Router Connected",
                    result = "Successfully authenticated and synced HG630 V2 telemetry."
                )
            } else {
                activityLogRepository.log(
                    component = "Router",
                    level = "WARN",
                    operation = "Router Connect Failed",
                    result = "Authentication failed for $gatewayIp",
                    errorDetails = result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun connectRouterDemoMode() {
        viewModelScope.launch {
            routerRepository.connectDemoMode()
            activityLogRepository.log(
                component = "Router",
                level = "INFO",
                operation = "Router Demo Mode",
                result = "Connected in interactive Huawei HG630 V2 simulation mode"
            )
        }
    }

    fun disconnectRouter() {
        viewModelScope.launch {
            routerRepository.disconnect()
            activityLogRepository.log(
                component = "Router",
                level = "INFO",
                operation = "Router Disconnected",
                result = "Closed active HG630 V2 session."
            )
        }
    }

    fun refreshRouterDashboard() {
        viewModelScope.launch {
            val res = routerRepository.refreshDashboard()
            if (res.isFailure) {
                activityLogRepository.log(
                    component = "Router",
                    level = "WARN",
                    operation = "Telemetry Refresh Failed",
                    result = res.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                )
            }
        }
    }

    fun updateRouterWifi(ssid: String, password: String?, enabled: Boolean?, channel: String?) {
        viewModelScope.launch {
            activityLogRepository.log(
                component = "Router",
                level = "INFO",
                operation = "Update Wi-Fi",
                result = "Applying Wi-Fi changes (SSID: $ssid, Enabled: $enabled, Channel: $channel)"
            )
            val res = routerRepository.updateWifi(
                WifiUpdatePayload(
                    ssid = ssid,
                    password = password?.ifBlank { null },
                    enabled = enabled,
                    channel = channel
                )
            )
            if (res.isSuccess) {
                activityLogRepository.log(
                    component = "Router",
                    level = "INFO",
                    operation = "Wi-Fi Updated",
                    result = "Wi-Fi settings applied successfully on HG630 V2."
                )
            } else {
                activityLogRepository.log(
                    component = "Router",
                    level = "ERROR",
                    operation = "Wi-Fi Update Failed",
                    result = "Router rejected Wi-Fi configuration",
                    errorDetails = res.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun rebootRouter() {
        viewModelScope.launch {
            activityLogRepository.log(
                component = "Router",
                level = "WARN",
                operation = "Reboot Router",
                result = "Sending graceful reboot command to HG630 V2."
            )
            routerRepository.rebootRouter()
        }
    }

    fun runArpAudit() {
        val net = _networkInfo.value ?: return
        val iface = net.interfaceName
        val gwIp = net.gatewayIp
        val knownGw = _currentOnlineDevices.value.firstOrNull { it.isGateway }
        val gwMac = knownGw?.mac

        viewModelScope.launch(Dispatchers.IO) {
            activityLogRepository.log("Security", "INFO", "ARP Audit", "Auditing ARP table on $iface")
            val report = securityMonitor.arpAnalyzer.auditArpCache(gwIp, gwMac, iface)
            _arpIntegrityReport.value = report
            activityLogRepository.log(
                "Security",
                if (report.isConsistent) "INFO" else "WARN",
                "ARP Audit Result",
                report.statusMessage
            )
        }
    }

    fun auditGatewayIntegrity() {
        val net = _networkInfo.value ?: return
        val iface = net.interfaceName
        val gwIp = net.gatewayIp
        val knownGw = _currentOnlineDevices.value.firstOrNull { it.isGateway }
        val gwMac = knownGw?.mac

        viewModelScope.launch(Dispatchers.IO) {
            activityLogRepository.log("Diagnostics", "INFO", "Gateway Integrity", "Verifying Gateway $gwIp")
            val report = diagnosticsEngine.auditGatewayIntegrity(gwIp, gwMac, securityMonitor.arpAnalyzer, iface)
            _arpIntegrityReport.value = report
            _operationFeedback.value = report.statusMessage
        }
    }

    fun attemptBlockDevice(device: DiscoveredDevice) {
        val currentIp = _networkInfo.value?.ip
        val gatewayIp = _networkInfo.value?.gatewayIp
        val iface = _networkInfo.value?.interfaceName ?: "wlan0"
        val context = getApplication<Application>()

        if (device.isCurrentDevice || device.ip == currentIp) {
            _operationFeedback.value = context.getString(R.string.cannot_block_current_device)
            return
        }
        if (device.isGateway || device.ip == gatewayIp) {
            _operationFeedback.value = context.getString(R.string.cannot_block_gateway)
            return
        }
        if (device.isProtected || protectedDevicesManager.isProtected(device.ip)) {
            _operationFeedback.value = context.getString(R.string.cannot_block_protected)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            var backendUsed = EnforcementBackend.ROUTER_API
            var blockSucceeded = false
            var failureDetails: String? = null

            // 1. Try Router API if router is supported
            if (_routerCapability.value?.isSupported == true) {
                val routerRes = routerController.blockDevice(device.mac ?: "", device.ip)
                if (routerRes.isSuccess) {
                    blockSucceeded = true
                    backendUsed = EnforcementBackend.ROUTER_API
                } else {
                    failureDetails = routerRes.exceptionOrNull()?.message
                }
            }

            // 2. Fallback to privileged Root/KernelSU iptables firewall block with traffic path verification
            if (!blockSucceeded) {
                val blockRes = FirewallEnforcementEngine.enforceVerifiedBlock(
                    targetIp = device.ip,
                    targetMac = device.mac,
                    localIp = currentIp,
                    gatewayIp = gatewayIp,
                    interfaceName = iface
                )
                if (blockRes.success) {
                    blockSucceeded = true
                    backendUsed = EnforcementBackend.ROOT_IPTABLES
                } else {
                    failureDetails = blockRes.failureReason
                }
            }

            if (!blockSucceeded) {
                val errorMsg = failureDetails ?: context.getString(R.string.blocking_unsupported)
                _operationFeedback.value = errorMsg
                activityLogRepository.log("AccessControl", "WARN", "Block Device Attempt Failed", "${device.ip}: $errorMsg")
            } else {
                val updated = device.copy(accessStatus = DeviceAccessStatus.BLOCKED)
                deviceRepository.saveDevice(updated)
                _currentOnlineDevices.value = _currentOnlineDevices.value.map { if (it.ip == device.ip) updated else it }
                if (_selectedDevice.value?.ip == device.ip) {
                    _selectedDevice.value = updated
                }
                ruleStateManager.registerBlockedDevice(
                    ip = device.ip,
                    mac = device.mac,
                    name = device.hostname ?: device.vendor ?: device.ip,
                    backend = backendUsed
                )
                val successNote = if (backendUsed == EnforcementBackend.ROUTER_API) "via Router API" else "via Root Firewall"
                _operationFeedback.value = "Device blocked $successNote"
                activityLogRepository.log("AccessControl", "INFO", "Device Blocked", "IP: ${device.ip} ($successNote)")
            }
        }
    }

    fun attemptUnblockDevice(device: DiscoveredDevice) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            routerController.unblockDevice(device.mac ?: "")
            FirewallEnforcementEngine.enforceVerifiedUnblock(device.ip)
            ruleStateManager.registerUnblockedDevice(device.ip)

            val updated = device.copy(accessStatus = DeviceAccessStatus.ONLINE)
            deviceRepository.saveDevice(updated)
            _currentOnlineDevices.value = _currentOnlineDevices.value.map { if (it.ip == device.ip) updated else it }
            if (_selectedDevice.value?.ip == device.ip) {
                _selectedDevice.value = updated
            }
            _operationFeedback.value = "Device unblocked"
            activityLogRepository.log("AccessControl", "INFO", "Device Unblocked", "IP: ${device.ip}")
        }
    }

    fun blockSelectedDevices() {
        val selectedIps = _selectedDeviceIps.value
        if (selectedIps.isEmpty()) return

        val currentIp = _networkInfo.value?.ip
        val gatewayIp = _networkInfo.value?.gatewayIp
        val iface = _networkInfo.value?.interfaceName ?: "wlan0"
        val context = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            val targets = _currentOnlineDevices.value.filter { dev ->
                selectedIps.contains(dev.ip) &&
                !dev.isCurrentDevice &&
                (dev.ip != currentIp) &&
                !dev.isGateway &&
                (dev.ip != gatewayIp) &&
                !dev.isProtected &&
                !protectedDevicesManager.isProtected(dev.ip)
            }

            if (targets.isEmpty()) {
                _operationFeedback.value = context.getString(R.string.cannot_block_protected)
                return@launch
            }

            var blockedCount = 0
            var unsupportedReported = false

            for (target in targets) {
                var success = false
                var backend = EnforcementBackend.ROUTER_API

                if (_routerCapability.value?.isSupported == true) {
                    val res = routerController.blockDevice(target.mac ?: "", target.ip)
                    if (res.isSuccess) {
                        success = true
                        backend = EnforcementBackend.ROUTER_API
                    }
                }

                if (!success) {
                    val fwRes = FirewallEnforcementEngine.enforceVerifiedBlock(
                        targetIp = target.ip,
                        targetMac = target.mac,
                        localIp = currentIp,
                        gatewayIp = gatewayIp,
                        interfaceName = iface
                    )
                    if (fwRes.success) {
                        success = true
                        backend = EnforcementBackend.ROOT_IPTABLES
                    }
                }

                if (!success) {
                    if (!unsupportedReported) {
                        _operationFeedback.value = context.getString(R.string.blocking_unsupported)
                        unsupportedReported = true
                    }
                    activityLogRepository.log("AccessControl", "WARN", "Block Selected Failed", "IP: ${target.ip} - ${context.getString(R.string.blocking_unsupported)}")
                } else {
                    val updated = target.copy(accessStatus = DeviceAccessStatus.BLOCKED)
                    deviceRepository.saveDevice(updated)
                    ruleStateManager.registerBlockedDevice(target.ip, target.mac, target.hostname ?: target.vendor ?: target.ip, backend)
                    blockedCount++
                }
            }

            // Sync live list state
            val blockedIps = ruleStateManager.activeRules.value.map { it.targetIdentifier }.toSet()
            _currentOnlineDevices.value = _currentOnlineDevices.value.map { dev ->
                if (blockedIps.contains(dev.ip)) dev.copy(accessStatus = DeviceAccessStatus.BLOCKED) else dev
            }

            if (blockedCount > 0) {
                _operationFeedback.value = "Blocked $blockedCount devices"
                activityLogRepository.log("AccessControl", "INFO", "Multi-Block Completed", "Blocked $blockedCount devices")
            }
            clearDeviceSelection()
        }
    }

    fun allowSelectedDevices() {
        val selectedIps = _selectedDeviceIps.value
        if (selectedIps.isEmpty()) return

        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val targets = _currentOnlineDevices.value.filter { selectedIps.contains(it.ip) }

            var unblockedCount = 0

            for (target in targets) {
                routerController.unblockDevice(target.mac ?: "")
                FirewallEnforcementEngine.enforceVerifiedUnblock(target.ip)
                ruleStateManager.registerUnblockedDevice(target.ip)
                val updated = target.copy(accessStatus = DeviceAccessStatus.ONLINE)
                deviceRepository.saveDevice(updated)
                unblockedCount++
            }

            val blockedIps = ruleStateManager.activeRules.value.map { it.targetIdentifier }.toSet()
            _currentOnlineDevices.value = _currentOnlineDevices.value.map { dev ->
                if (!blockedIps.contains(dev.ip)) dev.copy(accessStatus = DeviceAccessStatus.ONLINE) else dev
            }

            if (unblockedCount > 0) {
                _operationFeedback.value = "Allowed $unblockedCount devices"
            }
            clearDeviceSelection()
        }
    }

    fun blockAllUsers() {
        val currentIp = _networkInfo.value?.ip
        val gatewayIp = _networkInfo.value?.gatewayIp
        val iface = _networkInfo.value?.interfaceName ?: "wlan0"
        val context = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            // OPERATE STRICTLY ON CURRENT VERIFIED ONLINE DEVICES
            val liveOnline = _currentOnlineDevices.value.filter { it.status == DeviceStatus.ONLINE }
            val eligibleToBlock = liveOnline.filter { dev ->
                !dev.isCurrentDevice &&
                (dev.ip != currentIp) &&
                !dev.isGateway &&
                (dev.ip != gatewayIp) &&
                !dev.isProtected &&
                !protectedDevicesManager.isProtected(dev.ip)
            }

            val skippedCount = liveOnline.size - eligibleToBlock.size

            if (eligibleToBlock.isEmpty()) {
                _operationFeedback.value = "No eligible devices found to block (Skipped $skippedCount: current/gateway/protected)."
                return@launch
            }

            var succeededCount = 0
            var failedCount = 0
            var failureReason: String? = null

            for (target in eligibleToBlock) {
                var success = false
                var backend = EnforcementBackend.ROUTER_API

                if (_routerCapability.value?.isSupported == true) {
                    val res = routerController.blockDevice(target.mac ?: "", target.ip)
                    if (res.isSuccess) {
                        success = true
                        backend = EnforcementBackend.ROUTER_API
                    }
                }

                if (!success) {
                    val fwRes = FirewallEnforcementEngine.enforceVerifiedBlock(
                        targetIp = target.ip,
                        targetMac = target.mac,
                        localIp = currentIp,
                        gatewayIp = gatewayIp,
                        interfaceName = iface
                    )
                    if (fwRes.success) {
                        success = true
                        backend = EnforcementBackend.ROOT_IPTABLES
                    } else {
                        failureReason = fwRes.failureReason
                    }
                }

                if (success) {
                    val blockedDev = target.copy(accessStatus = DeviceAccessStatus.BLOCKED)
                    deviceRepository.saveDevice(blockedDev)
                    ruleStateManager.registerBlockedDevice(target.ip, target.mac, target.hostname ?: target.vendor ?: target.ip, backend)
                    succeededCount++
                } else {
                    failedCount++
                }
            }

            // Sync live list state
            val activeBlockedIps = ruleStateManager.activeRules.value.map { it.targetIdentifier }.toSet()
            _currentOnlineDevices.value = _currentOnlineDevices.value.map { dev ->
                if (activeBlockedIps.contains(dev.ip)) {
                    dev.copy(accessStatus = DeviceAccessStatus.BLOCKED)
                } else dev
            }

            val summaryMsg = buildString {
                append("Block All Result — Succeeded: $succeededCount, Failed: $failedCount, Skipped: $skippedCount")
                if (failedCount > 0 && failureReason != null) {
                    append("\nNote: $failureReason")
                }
            }
            _operationFeedback.value = summaryMsg
            activityLogRepository.log("AccessControl", if (failedCount == 0) "INFO" else "WARN", "Block All Completed", summaryMsg)
        }
    }

    fun unblockAllUsers() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val allDevs = deviceRepository.getAllDevices()
            val blockedDevs = allDevs.filter { it.accessStatus == DeviceAccessStatus.BLOCKED }

            if (blockedDevs.isEmpty()) {
                _operationFeedback.value = "No blocked devices found to restore."
                return@launch
            }

            var unblockedCount = 0

            for (target in blockedDevs) {
                routerController.unblockDevice(target.mac ?: "")
                FirewallEnforcementEngine.enforceVerifiedUnblock(target.ip)
                ruleStateManager.registerUnblockedDevice(target.ip)
                deviceRepository.saveDevice(target.copy(accessStatus = DeviceAccessStatus.ONLINE))
                unblockedCount++
            }

            _currentOnlineDevices.value = _currentOnlineDevices.value.map { dev ->
                dev.copy(accessStatus = DeviceAccessStatus.ONLINE)
            }

            if (unblockedCount > 0) {
                _operationFeedback.value = "Restored access for $unblockedCount devices"
                activityLogRepository.log("AccessControl", "INFO", "Unblock All Completed", "Successfully unblocked $unblockedCount devices")
            }
        }
    }

    // Smart Exit & Background Protection Actions
    fun hasActiveRestrictions(): Boolean {
        return ruleStateManager.hasActiveRestrictions()
    }

    fun openSmartExitDialog() {
        _showSmartExitDialog.value = true
    }

    fun dismissSmartExitDialog() {
        _showSmartExitDialog.value = false
    }

    fun exitAndRestore(activity: Activity?) {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = ruleStateManager.rollbackAllAppRules(deviceRepository, routerController)
            activityLogRepository.log("SmartExit", "INFO", "Rollback Executed", summary.message)
            withContext(Dispatchers.Main) {
                _showSmartExitDialog.value = false
                activity?.finish()
            }
        }
    }

    fun exitAndKeepProtection(activity: Activity?) {
        viewModelScope.launch(Dispatchers.IO) {
            ruleStateManager.setBackgroundProtectionEnabled(true)
            NetworkProtectionService.startService(getApplication())
            activityLogRepository.log("SmartExit", "INFO", "Background Protection Maintained", "Restrictions enforced in background")
            withContext(Dispatchers.Main) {
                _showSmartExitDialog.value = false
                // Moves the task to background so protection stays alive smoothly
                val moved = activity?.moveTaskToBack(true) ?: false
                if (!moved) {
                    activity?.finish()
                }
            }
        }
    }

    fun rollbackAllNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = ruleStateManager.rollbackAllAppRules(deviceRepository, routerController)
            _operationFeedback.value = summary.message
            activityLogRepository.log("Protection", "INFO", "Manual Rollback", summary.message)
        }
    }

    fun checkPrivileges(forceRefresh: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            _rootStatus.value = PrivilegeManager.checkRootStatus(forceRefresh)
            _kernelSuStatus.value = PrivilegeManager.checkKernelSuStatus(forceRefresh)
            refreshCapabilities(forceRefresh = false)
        }
    }

    fun runCompatibilityAudit(forceRefresh: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = CompatibilityScanner.auditDevice(getApplication())
            _compatibilityAudit.value = result
            refreshCapabilities(forceRefresh = false)
        }
    }

    fun selectDevice(device: DiscoveredDevice?) {
        _selectedDevice.value = device
    }

    fun clearFeedback() {
        _operationFeedback.value = null
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            activityLogRepository.clearLogs()
        }
    }

    fun clearAlerts() {
        securityMonitor.clearAlerts()
    }

    fun checkTrafficControlCapability() {
        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            val currentIp = _networkInfo.value?.ip
            val gatewayIp = _networkInfo.value?.gatewayIp
            val cap = TrafficControlEngine.evaluateCapability(networkInterface = iface, localIp = currentIp, gatewayIp = gatewayIp)
            _trafficControlCapability.value = cap
        }
    }

    fun setDeviceBandwidthLimit(device: DiscoveredDevice, rateKbps: Long?) {
        val context = getApplication<Application>()
        val currentIp = _networkInfo.value?.ip

        val gatewayIp = _networkInfo.value?.gatewayIp

        if (device.isCurrentDevice || device.ip == currentIp) {
            _operationFeedback.value = context.getString(R.string.cannot_block_current_device)
            return
        }
        if (device.isGateway || device.ip == gatewayIp) {
            _operationFeedback.value = context.getString(R.string.cannot_block_gateway)
            return
        }
        if (device.isProtected || protectedDevicesManager.isProtected(device.ip)) {
            _operationFeedback.value = context.getString(R.string.cannot_block_protected)
            return
        }

        if (rateKbps == null || rateKbps <= 0) {
            removeDeviceBandwidthLimit(device)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            val cap = TrafficControlEngine.evaluateCapability(networkInterface = iface, localIp = currentIp, gatewayIp = gatewayIp)
            _trafficControlCapability.value = cap

            if (!cap.isSupported) {
                val reason = cap.unsupportedReason ?: "Kernel Traffic Control (tc/HTB) is not supported on this device/configuration."
                _operationFeedback.value = reason
                activityLogRepository.log("TrafficControl", "WARN", "Bandwidth Limit Failed", "Device: ${device.ip}, Reason: $reason")
                return@launch
            }

            val gatewayIp = _networkInfo.value?.gatewayIp
            val res = TrafficControlEngine.applyDeviceRateLimit(iface, device.ip, rateKbps, currentIp, gatewayIp)
            if (res.isSuccess) {
                val updated = device.copy(bandwidthLimitKbps = rateKbps)
                deviceRepository.saveDevice(updated)
                _currentOnlineDevices.value = _currentOnlineDevices.value.map { if (it.ip == device.ip) updated else it }
                if (_selectedDevice.value?.ip == device.ip) {
                    _selectedDevice.value = updated
                }
                ruleStateManager.registerRateLimitedDevice(
                    ip = device.ip,
                    mac = device.mac,
                    name = device.hostname ?: device.vendor ?: device.ip,
                    rateKbps = rateKbps
                )
                val speedDisplay = if (rateKbps >= 1000) "${rateKbps / 1000} Mbps" else "$rateKbps Kbps"
                _operationFeedback.value = "Speed limit set to $speedDisplay for ${device.ip}"
                activityLogRepository.log("TrafficControl", "INFO", "Speed Limit Applied", "IP: ${device.ip}, Limit: $speedDisplay")
            } else {
                val err = res.exceptionOrNull()?.message ?: "Failed to apply traffic control rule"
                _operationFeedback.value = err
                activityLogRepository.log("TrafficControl", "ERROR", "Speed Limit Failed", "IP: ${device.ip}, Error: $err")
            }
        }
    }

    fun removeDeviceBandwidthLimit(device: DiscoveredDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            TrafficControlEngine.removeDeviceRateLimit(iface, device.ip)
            ruleStateManager.registerRemovedRateLimit(device.ip)

            val updated = device.copy(bandwidthLimitKbps = null)
            deviceRepository.saveDevice(updated)
            _currentOnlineDevices.value = _currentOnlineDevices.value.map { if (it.ip == device.ip) updated else it }
            if (_selectedDevice.value?.ip == device.ip) {
                _selectedDevice.value = updated
            }
            _operationFeedback.value = "Bandwidth restriction removed for ${device.ip}"
            activityLogRepository.log("TrafficControl", "INFO", "Speed Limit Removed", "IP: ${device.ip}")
        }
    }

    fun setGlobalBandwidthLimit(rateKbps: Long?) {
        val context = getApplication<Application>()
        if (rateKbps == null || rateKbps <= 0) {
            removeGlobalBandwidthLimit()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            val cap = TrafficControlEngine.evaluateCapability(iface)
            _trafficControlCapability.value = cap

            if (!cap.isSupported) {
                val reason = cap.unsupportedReason ?: "Traffic control unsupported on this device/configuration"
                _operationFeedback.value = reason
                activityLogRepository.log("TrafficControl", "WARN", "Global Limit Failed", reason)
                return@launch
            }

            val res = TrafficControlEngine.applyGlobalRateLimit(iface, rateKbps)
            if (res.isSuccess) {
                ruleStateManager.registerGlobalRateLimit(rateKbps)
                val speedDisplay = if (rateKbps >= 1000) "${rateKbps / 1000} Mbps" else "$rateKbps Kbps"
                _operationFeedback.value = "Global speed limit set to $speedDisplay"
                activityLogRepository.log("TrafficControl", "INFO", "Global Speed Limit Applied", "Limit: $speedDisplay")
            } else {
                val err = res.exceptionOrNull()?.message ?: "Failed to apply global limit"
                _operationFeedback.value = err
            }
        }
    }

    fun removeGlobalBandwidthLimit() {
        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            TrafficControlEngine.removeGlobalRateLimit(iface)
            ruleStateManager.removeGlobalRateLimit()
            _operationFeedback.value = "Global bandwidth restriction removed"
            activityLogRepository.log("TrafficControl", "INFO", "Global Limit Removed", "Default unrestricted rate restored")
        }
    }

    fun restoreDevice(device: DiscoveredDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"

            // 1. Remove router block and firewall block
            routerController.unblockDevice(device.mac ?: "")
            FirewallEnforcementEngine.enforceVerifiedUnblock(device.ip)
            ruleStateManager.registerUnblockedDevice(device.ip)

            // 2. Remove traffic control rate limit
            TrafficControlEngine.removeDeviceRateLimit(iface, device.ip)
            ruleStateManager.registerRemovedRateLimit(device.ip)

            // 3. Update repository entity
            val updated = device.copy(
                accessStatus = DeviceAccessStatus.ONLINE,
                bandwidthLimitKbps = null
            )
            deviceRepository.saveDevice(updated)
            _currentOnlineDevices.value = _currentOnlineDevices.value.map { if (it.ip == device.ip) updated else it }
            if (_selectedDevice.value?.ip == device.ip) {
                _selectedDevice.value = updated
            }

            _operationFeedback.value = "Restored all network access for ${device.ip}"
            activityLogRepository.log("Recovery", "INFO", "Device Restored", "IP: ${device.ip} unblocked and unthrottled")
        }
    }

    fun restoreAllNetworkControls() {
        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            val summary = ruleStateManager.rollbackAllAppRules(deviceRepository, routerController, iface)
            _operationFeedback.value = summary.message
            activityLogRepository.log("Recovery", "INFO", "Restore All Executed", summary.message)
        }
    }

    fun emergencyResetNetworkControls() {
        viewModelScope.launch(Dispatchers.IO) {
            val iface = _networkInfo.value?.interfaceName ?: "wlan0"
            val summary = ruleStateManager.emergencyResetAll(deviceRepository, routerController, iface)
            _operationFeedback.value = summary.message
            activityLogRepository.log("Recovery", "WARN", "Emergency Reset Executed", summary.message)
        }
    }

    /**
     * Compiles a comprehensive diagnostic report containing device specs, kernel/root status,
     * network role & interface details, reasons for feature limitations, and all recorded activity/error logs.
     * Ready for instant clipboard copying or sharing.
     */
    fun buildDiagnosticReport(logsList: List<ActivityLogEntity>): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val now = dateFormat.format(Date())

        sb.appendLine("==================================================")
        sb.appendLine("           NETMANAGER PRO - DIAGNOSTIC REPORT     ")
        sb.appendLine("==================================================")
        sb.appendLine("Generated At: $now")
        sb.appendLine()

        sb.appendLine("--- [DEVICE & PLATFORM SPECIFICATIONS] ---")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Model: ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android Release: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("App Version: ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
        sb.appendLine()

        sb.appendLine("--- [PRIVILEGE & KERNEL STATUS] ---")
        sb.appendLine("Root Status: ${_rootStatus.value.name}")
        sb.appendLine("KernelSU Status: ${_kernelSuStatus.value.name}")
        val rootAudit = _capabilityAuditResult.value?.rootAudit
        if (rootAudit != null) {
            sb.appendLine("Root UID 0 Verified: ${rootAudit.isUid0Verified}")
            sb.appendLine("Root Binary Path: ${rootAudit.binaryPath ?: "None"}")
            if (!rootAudit.failureReason.isNullOrBlank()) {
                sb.appendLine("Root Failure Reason: ${rootAudit.failureReason}")
            }
        }
        val ksuAudit = _capabilityAuditResult.value?.ksuAudit
        if (ksuAudit != null && ksuAudit.detectionEvidence.isNotEmpty()) {
            sb.appendLine("KernelSU Evidence: ${ksuAudit.detectionEvidence.joinToString("; ")}")
        }
        sb.appendLine()

        sb.appendLine("--- [NETWORK INTERFACE & ROUTING] ---")
        val net = _networkInfo.value
        sb.appendLine("Active Interface: ${net?.interfaceName ?: "No active network"}")
        sb.appendLine("Assigned IP: ${net?.ip ?: "Unknown"}/${net?.prefixLength ?: 0}")
        sb.appendLine("Default Gateway: ${net?.gatewayIp ?: "Unknown"}")
        sb.appendLine("DNS Servers: ${net?.dnsServers?.joinToString(", ") ?: "None"}")
        val trafficPath = _capabilityAuditResult.value?.trafficPathReport
        if (trafficPath != null) {
            sb.appendLine("Phone Role: ${trafficPath.role.name}")
            sb.appendLine("Can Local Firewall Block Remote: ${trafficPath.canLocalFirewallBlockRemoteClients}")
            sb.appendLine("Traffic Path Explanation: ${trafficPath.explanation}")
        }
        sb.appendLine()

        sb.appendLine("--- [FEATURE AUDIT & LIMITATIONS (WHY FEATURES WORK OR FAIL)] ---")
        val audit = _capabilityAuditResult.value
        if (audit != null) {
            for ((_, cap) in audit.capabilities) {
                sb.appendLine("• ${cap.name}: [${cap.status.name}]")
                sb.appendLine("   - Status Reason: ${cap.reason}")
                sb.appendLine("   - Requirements: ${cap.requirements}")
                sb.appendLine("   - Verification: ${cap.verification}")
            }
        } else {
            sb.appendLine("Capabilities audit pending or not yet initialized.")
        }
        sb.appendLine()

        sb.appendLine("--- [GATEWAY / ROUTER INTEGRATION] ---")
        val router = _routerCapability.value
        if (router != null) {
            sb.appendLine("Supported: ${router.isSupported}")
            sb.appendLine("Model / Interface: ${router.detectedModel}")
            sb.appendLine("Control Endpoint: ${router.endpoint}")
            if (router.unsupportedReasons.isNotEmpty()) {
                sb.appendLine("Limitations / Reasons: ${router.unsupportedReasons.joinToString("; ")}")
            }
        } else {
            sb.appendLine("Router probe not executed yet.")
        }
        sb.appendLine()

        sb.appendLine("--- [ACTIVE ENFORCED RULES] ---")
        val rules = activeEnforcedRules.value
        sb.appendLine("Enforced Rule Count: ${rules.size}")
        for (rule in rules) {
            sb.appendLine(" - Target: ${rule.targetIdentifier} (${rule.targetLabel}) | Type: ${rule.type} | Backend: ${rule.backend}")
        }
        sb.appendLine()

        sb.appendLine("--- [RECORDED SYSTEM ACTIVITY & ERROR LOGS (${logsList.size})] ---")
        if (logsList.isEmpty()) {
            sb.appendLine("No logs recorded yet.")
        } else {
            for (log in logsList) {
                val time = dateFormat.format(Date(log.timestamp))
                sb.appendLine("[$time] [${log.level}] [${log.component}] ${log.operation}")
                sb.appendLine("  Result: ${log.result}")
                if (!log.errorDetails.isNullOrBlank()) {
                    sb.appendLine("  Error / Reason: ${log.errorDetails}")
                }
                sb.appendLine("--------------------------------------------------")
            }
        }
        sb.appendLine("==================================================")
        sb.appendLine("                 END OF REPORT                    ")
        sb.appendLine("==================================================")

        return sb.toString()
    }
}
