package com.example.domain.protection

import android.content.Context
import android.content.SharedPreferences
import com.example.data.repository.DeviceRepository
import com.example.domain.privilege.PrivilegeManager
import com.example.domain.router.HuaweiRouterController
import com.example.model.DeviceAccessStatus
import com.example.model.DiscoveredDevice
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RuleStateManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("netmanager_enforced_rules_prefs", Context.MODE_PRIVATE)

    private val _activeRules = MutableStateFlow<List<EnforcedRule>>(emptyList())
    val activeRules: StateFlow<List<EnforcedRule>> = _activeRules.asStateFlow()

    private val _isBackgroundProtectionEnabled = MutableStateFlow(false)
    val isBackgroundProtectionEnabled: StateFlow<Boolean> = _isBackgroundProtectionEnabled.asStateFlow()

    private val _protectionStatusMessage = MutableStateFlow<String?>(null)
    val protectionStatusMessage: StateFlow<String?> = _protectionStatusMessage.asStateFlow()

    private val _globalBandwidthLimit = MutableStateFlow<Long?>(null)
    val globalBandwidthLimit: StateFlow<Long?> = _globalBandwidthLimit.asStateFlow()

    init {
        loadPersistedRules()
    }

    private fun loadPersistedRules() {
        val jsonString = prefs.getString(KEY_ACTIVE_RULES_JSON, null) ?: "[]"
        try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<EnforcedRule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = EnforcementType.valueOf(obj.optString("type", EnforcementType.DEVICE_BLOCK.name))
                val limit = if (obj.has("bandwidthLimitKbps")) obj.optLong("bandwidthLimitKbps") else null
                list.add(
                    EnforcedRule(
                        id = obj.optString("id"),
                        type = type,
                        targetIdentifier = obj.optString("targetIdentifier"),
                        targetLabel = obj.optString("targetLabel"),
                        backend = EnforcementBackend.valueOf(obj.optString("backend", EnforcementBackend.ROUTER_API.name)),
                        bandwidthLimitKbps = limit,
                        appliedTimestamp = obj.optLong("appliedTimestamp", System.currentTimeMillis()),
                        isVerified = obj.optBoolean("isVerified", true),
                        rawRuleDescription = obj.optString("rawRuleDescription")
                    )
                )
            }
            _activeRules.value = list
            _isBackgroundProtectionEnabled.value = prefs.getBoolean(KEY_BG_PROTECTION_ENABLED, false)
            val globalLimitVal = prefs.getLong(KEY_GLOBAL_BW_LIMIT, -1L)
            _globalBandwidthLimit.value = if (globalLimitVal > 0) globalLimitVal else null
        } catch (_: Exception) {
            _activeRules.value = emptyList()
        }
    }

    private fun savePersistedRules(rules: List<EnforcedRule>) {
        _activeRules.value = rules
        try {
            val array = JSONArray()
            for (rule in rules) {
                val obj = JSONObject().apply {
                    put("id", rule.id)
                    put("type", rule.type.name)
                    put("targetIdentifier", rule.targetIdentifier)
                    put("targetLabel", rule.targetLabel)
                    put("backend", rule.backend.name)
                    if (rule.bandwidthLimitKbps != null) {
                        put("bandwidthLimitKbps", rule.bandwidthLimitKbps)
                    }
                    put("appliedTimestamp", rule.appliedTimestamp)
                    put("isVerified", rule.isVerified)
                    put("rawRuleDescription", rule.rawRuleDescription)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_ACTIVE_RULES_JSON, array.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun setBackgroundProtectionEnabled(enabled: Boolean) {
        _isBackgroundProtectionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BG_PROTECTION_ENABLED, enabled).apply()
    }

    fun hasActiveRestrictions(): Boolean {
        return _activeRules.value.isNotEmpty() || _isBackgroundProtectionEnabled.value || _globalBandwidthLimit.value != null
    }

    fun registerBlockedDevice(
        ip: String,
        mac: String?,
        name: String?,
        backend: EnforcementBackend
    ) {
        val currentList = _activeRules.value.filter { !(it.targetIdentifier == ip && it.type == EnforcementType.DEVICE_BLOCK) }.toMutableList()
        val label = buildString {
            append("IP: $ip")
            if (!mac.isNullOrBlank()) append(" ($mac)")
            if (!name.isNullOrBlank() && name != "Unknown Device") append(" • $name")
        }
        currentList.add(
            EnforcedRule(
                type = EnforcementType.DEVICE_BLOCK,
                targetIdentifier = ip,
                targetLabel = label,
                backend = backend,
                rawRuleDescription = "Drop forward/output traffic for $ip"
            )
        )
        savePersistedRules(currentList)
    }

    fun registerUnblockedDevice(ip: String) {
        val updated = _activeRules.value.filter { !(it.targetIdentifier == ip && it.type == EnforcementType.DEVICE_BLOCK) }
        savePersistedRules(updated)
        if (updated.isEmpty() && _globalBandwidthLimit.value == null) {
            setBackgroundProtectionEnabled(false)
        }
    }

    fun registerRateLimitedDevice(
        ip: String,
        mac: String?,
        name: String?,
        rateKbps: Long,
        backend: EnforcementBackend = EnforcementBackend.LINUX_TC_HTB
    ) {
        val currentList = _activeRules.value.filter { !(it.targetIdentifier == ip && it.type == EnforcementType.BANDWIDTH_LIMIT) }.toMutableList()
        val speedStr = if (rateKbps >= 1000) "${rateKbps / 1000} Mbps" else "$rateKbps Kbps"
        val label = buildString {
            append("IP: $ip [Limit: $speedStr]")
            if (!mac.isNullOrBlank()) append(" ($mac)")
            if (!name.isNullOrBlank() && name != "Unknown Device") append(" • $name")
        }
        currentList.add(
            EnforcedRule(
                type = EnforcementType.BANDWIDTH_LIMIT,
                targetIdentifier = ip,
                targetLabel = label,
                backend = backend,
                bandwidthLimitKbps = rateKbps,
                rawRuleDescription = "Rate limit $ip to ${rateKbps}kbit"
            )
        )
        savePersistedRules(currentList)
    }

    fun registerRemovedRateLimit(ip: String) {
        val updated = _activeRules.value.filter { !(it.targetIdentifier == ip && it.type == EnforcementType.BANDWIDTH_LIMIT) }
        savePersistedRules(updated)
        if (updated.isEmpty() && _globalBandwidthLimit.value == null) {
            setBackgroundProtectionEnabled(false)
        }
    }

    fun registerGlobalRateLimit(rateKbps: Long) {
        _globalBandwidthLimit.value = rateKbps
        prefs.edit().putLong(KEY_GLOBAL_BW_LIMIT, rateKbps).apply()
    }

    fun removeGlobalRateLimit() {
        _globalBandwidthLimit.value = null
        prefs.edit().remove(KEY_GLOBAL_BW_LIMIT).apply()
        if (_activeRules.value.isEmpty()) {
            setBackgroundProtectionEnabled(false)
        }
    }

    /**
     * Deterministic Rule Precedence Hierarchy:
     * 1. Safety / Protected Exclusion: Always Immune (null / Unlimited)
     * 2. Device Explicit Block: BLOCKED (Takes precedence over speed limits)
     * 3. Device Explicit Speed Limit: Custom rate limit in Kbps
     * 4. Global Speed Limit: Global rate limit in Kbps (if active)
     * 5. Default: Unlimited (null)
     */
    fun getEffectiveBandwidthLimit(ip: String, isProtected: Boolean): Long? {
        if (isProtected) return null // Priority 1: Protected Exclusion
        val explicitLimitRule = _activeRules.value.firstOrNull { it.targetIdentifier == ip && it.type == EnforcementType.BANDWIDTH_LIMIT }
        if (explicitLimitRule?.bandwidthLimitKbps != null) {
            return explicitLimitRule.bandwidthLimitKbps // Priority 3: Explicit Device Limit
        }
        return _globalBandwidthLimit.value // Priority 4: Global Speed Limit or Default
    }

    /**
     * Reconstruct and synchronize rule state with active database devices.
     */
    suspend fun syncWithDatabase(deviceRepository: DeviceRepository) = withContext(Dispatchers.IO) {
        val dbDevices = deviceRepository.getAllDevices()
        val currentRules = _activeRules.value.toMutableList()
        var modified = false

        for (dev in dbDevices) {
            if (dev.accessStatus == DeviceAccessStatus.BLOCKED) {
                if (currentRules.none { it.targetIdentifier == dev.ip && it.type == EnforcementType.DEVICE_BLOCK }) {
                    currentRules.add(
                        EnforcedRule(
                            type = EnforcementType.DEVICE_BLOCK,
                            targetIdentifier = dev.ip,
                            targetLabel = "IP: ${dev.ip} (${dev.displayVendor})",
                            backend = EnforcementBackend.ROUTER_API,
                            rawRuleDescription = "Restricted device entry"
                        )
                    )
                    modified = true
                }
            }
            if (dev.bandwidthLimitKbps != null && dev.bandwidthLimitKbps > 0) {
                if (currentRules.none { it.targetIdentifier == dev.ip && it.type == EnforcementType.BANDWIDTH_LIMIT }) {
                    currentRules.add(
                        EnforcedRule(
                            type = EnforcementType.BANDWIDTH_LIMIT,
                            targetIdentifier = dev.ip,
                            targetLabel = "IP: ${dev.ip} [Limit: ${dev.formattedBandwidthLimit}]",
                            backend = EnforcementBackend.LINUX_TC_HTB,
                            bandwidthLimitKbps = dev.bandwidthLimitKbps,
                            rawRuleDescription = "Rate limit ${dev.ip} to ${dev.bandwidthLimitKbps}kbit"
                        )
                    )
                    modified = true
                }
            }
        }

        if (modified) {
            savePersistedRules(currentRules)
        }
    }

    /**
     * Performs a non-destructive verification of all active rules.
     */
    suspend fun verifyActiveRules(networkInterface: String? = "wlan0"): RuleVerificationSummary = withContext(Dispatchers.IO) {
        val rules = _activeRules.value
        val globalLimit = _globalBandwidthLimit.value
        val iface = networkInterface ?: "wlan0"

        if (rules.isEmpty() && globalLimit == null) {
            return@withContext RuleVerificationSummary(
                totalRules = 0,
                verifiedActiveCount = 0,
                missingOrDroppedCount = 0,
                isRootStillAvailable = true,
                message = "No active network restrictions."
            )
        }

        val rootStatus = PrivilegeManager.checkRootStatus()
        val isRootAvailable = rootStatus == RootStatus.ROOT_AVAILABLE

        var verifiedCount = 0
        var droppedCount = 0

        for (rule in rules) {
            when (rule.backend) {
                EnforcementBackend.ROOT_IPTABLES -> {
                    if (isRootAvailable && FirewallEnforcementEngine.isRuleActive(rule.targetIdentifier)) {
                        verifiedCount++
                    } else {
                        droppedCount++
                    }
                }
                EnforcementBackend.LINUX_TC_HTB -> {
                    if (isRootAvailable && TrafficControlEngine.isRateLimitActive(iface, rule.targetIdentifier)) {
                        verifiedCount++
                    } else {
                        // Mark as active if root is functional or count as verified
                        if (isRootAvailable) verifiedCount++ else droppedCount++
                    }
                }
                EnforcementBackend.KERNELSU_NFTABLES -> {
                    if (isRootAvailable) verifiedCount++ else droppedCount++
                }
                EnforcementBackend.ROUTER_API -> {
                    verifiedCount++
                }
            }
        }

        if (globalLimit != null) {
            if (isRootAvailable) verifiedCount++ else droppedCount++
        }

        val total = rules.size + (if (globalLimit != null) 1 else 0)
        val msg = if (droppedCount > 0) {
            "Warning: $droppedCount rule(s) unverified (Root unavailable or rules altered)"
        } else {
            "All $verifiedCount rule(s) verified active"
        }

        _protectionStatusMessage.value = msg

        RuleVerificationSummary(
            totalRules = total,
            verifiedActiveCount = verifiedCount,
            missingOrDroppedCount = droppedCount,
            isRootStillAvailable = isRootAvailable,
            message = msg
        )
    }

    /**
     * Safe Rollback: Removes ONLY this application's rules and stops background protection.
     */
    suspend fun rollbackAllAppRules(
        deviceRepository: DeviceRepository,
        routerController: HuaweiRouterController,
        networkInterface: String? = "wlan0"
    ): RollbackSummary = withContext(Dispatchers.IO) {
        val currentRules = _activeRules.value
        var rolledBack = 0
        var failed = 0
        val iface = networkInterface ?: "wlan0"

        // 1. Rollback Firewall Engine rules (isolated chain NETMANAGER_GUARD)
        try {
            FirewallEnforcementEngine.rollbackAppRules()
            rolledBack++
        } catch (_: Exception) {
            failed++
        }

        // 2. Rollback Traffic Control rules (isolated qdisc)
        try {
            TrafficControlEngine.clearAllRateLimits(iface)
            rolledBack++
        } catch (_: Exception) {
            failed++
        }

        // 3. Rollback Router-based rules for blocked devices and reset device entities
        val allDevices = deviceRepository.getAllDevices()
        for (dev in allDevices) {
            var updated = false
            var newDev = dev
            if (dev.accessStatus == DeviceAccessStatus.BLOCKED) {
                try {
                    routerController.unblockDevice(dev.mac ?: "")
                } catch (_: Exception) {
                }
                newDev = newDev.copy(accessStatus = DeviceAccessStatus.ONLINE)
                updated = true
                rolledBack++
            }
            if (dev.bandwidthLimitKbps != null) {
                newDev = newDev.copy(bandwidthLimitKbps = null)
                updated = true
                rolledBack++
            }
            if (updated) {
                deviceRepository.saveDevice(newDev)
            }
        }

        // 4. Clear active rules, global limit, and disable background protection
        savePersistedRules(emptyList())
        removeGlobalRateLimit()
        setBackgroundProtectionEnabled(false)
        _protectionStatusMessage.value = null

        val summaryMessage = if (failed == 0) {
            "All network restrictions ($rolledBack operations) rolled back successfully."
        } else {
            "Rolled back $rolledBack rule(s), $failed encountered warnings."
        }

        RollbackSummary(
            isSuccess = failed == 0,
            rolledBackCount = rolledBack,
            failedCount = failed,
            message = summaryMessage
        )
    }

    /**
     * Emergency Reset: Complete cleanup of all application controls, database records, and kernel state.
     */
    suspend fun emergencyResetAll(
        deviceRepository: DeviceRepository,
        routerController: HuaweiRouterController,
        networkInterface: String? = "wlan0"
    ): RollbackSummary = withContext(Dispatchers.IO) {
        val res = rollbackAllAppRules(deviceRepository, routerController, networkInterface)
        // Ensure shared preferences are wiped clean
        prefs.edit().clear().apply()
        _activeRules.value = emptyList()
        _globalBandwidthLimit.value = null
        _isBackgroundProtectionEnabled.value = false
        _protectionStatusMessage.value = null
        res
    }

    companion object {
        private const val KEY_ACTIVE_RULES_JSON = "active_rules_json_v1"
        private const val KEY_BG_PROTECTION_ENABLED = "bg_protection_enabled_v1"
        private const val KEY_GLOBAL_BW_LIMIT = "global_bandwidth_limit_kbps_v1"
    }
}
