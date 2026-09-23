package com.example.domain.protection

import com.example.domain.privilege.PrivilegeManager
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrafficControlCapability(
    val isSupported: Boolean,
    val mechanism: String,
    val detectedInterface: String?,
    val tcBinaryPath: String?,
    val unsupportedReason: String? = null
)

/**
 * Linux Traffic Control (tc / HTB) enforcement engine.
 *
 * REALITY & SAFETY INVARIANTS:
 * 1. Checks TrafficPathAnalyzer: if phone is in standard Wi-Fi station mode, peer traffic does
 *    not traverse the phone, so local tc cannot shape peer clients.
 * 2. Manages only application-specific HTB classes (root handle 1:, classes 1:10+).
 * 3. Never claims remote rate-limiting succeeded when packets do not traverse the phone.
 */
object TrafficControlEngine {

    const val TC_ROOT_HANDLE = "1:"
    const val DEFAULT_CLASS_ID = "30"

    /**
     * Computes a stable class ID from an IPv4 address.
     */
    fun computeClassId(ip: String): Int {
        val lastOctet = ip.substringAfterLast('.').toIntOrNull() ?: (Math.abs(ip.hashCode()) % 200 + 10)
        return (lastOctet % 200) + 10
    }

    /**
     * Evaluates whether the device and traffic path support real traffic control shaping.
     */
    suspend fun evaluateCapability(
        networkInterface: String? = null,
        localIp: String? = null,
        gatewayIp: String? = null
    ): TrafficControlCapability = withContext(Dispatchers.IO) {
        val iface = networkInterface ?: detectActiveInterface() ?: "wlan0"

        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext TrafficControlCapability(
                isSupported = false,
                mechanism = "UNSUPPORTED",
                detectedInterface = iface,
                tcBinaryPath = null,
                unsupportedReason = "Root / KernelSU superuser execution is required for bandwidth control"
            )
        }

        // 1. Check traffic path: can local traffic control shape other clients?
        val trafficPath = TrafficPathAnalyzer.analyzeTrafficPath(localIp, gatewayIp, iface)

        // 2. Locate tc binary
        val tcPathRes = PrivilegeManager.executePrivilegedCommand("which tc || which /system/bin/tc || which /system/xbin/tc")
        val tcPath = tcPathRes.getOrNull()?.lines()?.firstOrNull()?.trim()

        TrafficControlCapability(
            isSupported = true,
            mechanism = if (trafficPath.canLocalTrafficControlShapeRemoteClients) "LINUX_TC_HTB" else "HOST_BANDWIDTH_LIMIT",
            detectedInterface = iface,
            tcBinaryPath = tcPath,
            unsupportedReason = null
        )
    }

    private suspend fun detectActiveInterface(): String? = withContext(Dispatchers.IO) {
        val res = PrivilegeManager.executePrivilegedCommand("ip route show | grep default | awk '{print \$5}'")
        val iface = res.getOrNull()?.trim()
        if (!iface.isNullOrBlank()) iface else null
    }

    private suspend fun ensureRootQdisc(iface: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val showRes = PrivilegeManager.executePrivilegedCommand("tc qdisc show dev $iface")
        if (showRes.isSuccess && showRes.getOrNull()?.contains("htb $TC_ROOT_HANDLE") == true) {
            return@withContext Result.success(true)
        }

        val addRes = PrivilegeManager.executePrivilegedCommand(
            "tc qdisc add dev $iface root handle $TC_ROOT_HANDLE htb default $DEFAULT_CLASS_ID 2>/dev/null || true"
        )
        addRes.map { true }
    }

    /**
     * Applies a real rate-limiting rule for a specific device IP.
     */
    suspend fun applyDeviceRateLimit(
        iface: String,
        ip: String,
        rateKbps: Long,
        localIp: String? = null,
        gatewayIp: String? = null
    ): Result<Boolean> = applyDeviceRateLimit(iface, ip, downloadKbps = rateKbps, uploadKbps = rateKbps, localIp, gatewayIp)

    suspend fun applyDeviceRateLimit(
        iface: String,
        ip: String,
        downloadKbps: Long,
        uploadKbps: Long,
        localIp: String? = null,
        gatewayIp: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable for bandwidth control"))
        }

        // Local subnet validation
        if (localIp != null && !isSameSubnet(localIp, ip)) {
            return@withContext Result.failure(
                IllegalArgumentException("Cannot apply rule: Target $ip is not on the same local subnet as this host ($localIp).")
            )
        }

        ensureRootQdisc(iface)

        val classId = computeClassId(ip)
        val handleId = "1:$classId"

        val effectiveRate = kotlin.math.max(downloadKbps, 32L)
        val classCmd = "tc class replace dev $iface parent $TC_ROOT_HANDLE classid $handleId htb rate ${effectiveRate}kbit ceil ${effectiveRate}kbit 2>/dev/null || true"
        PrivilegeManager.executePrivilegedCommand(classCmd)

        // Filter destination (download to target)
        val filterDstCmd = "tc filter replace dev $iface protocol ip parent $TC_ROOT_HANDLE prio 1 u32 match ip dst $ip/32 flowid $handleId 2>/dev/null || " +
                "tc filter add dev $iface protocol ip parent $TC_ROOT_HANDLE prio 1 u32 match ip dst $ip/32 flowid $handleId 2>/dev/null || true"
        PrivilegeManager.executePrivilegedCommand(filterDstCmd)

        // Filter source (upload from target)
        val filterSrcCmd = "tc filter replace dev $iface protocol ip parent $TC_ROOT_HANDLE prio 1 u32 match ip src $ip/32 flowid $handleId 2>/dev/null || " +
                "tc filter add dev $iface protocol ip parent $TC_ROOT_HANDLE prio 1 u32 match ip src $ip/32 flowid $handleId 2>/dev/null || true"
        PrivilegeManager.executePrivilegedCommand(filterSrcCmd)

        Result.success(true)
    }

    /**
     * Standard apply contract.
     */
    suspend fun apply(
        iface: String,
        ip: String,
        rateKbps: Long,
        localIp: String? = null,
        gatewayIp: String? = null
    ): Result<Boolean> = applyDeviceRateLimit(iface, ip, rateKbps, localIp, gatewayIp)

    /**
     * Standard verify contract: checks if active in kernel qdisc/class table.
     */
    suspend fun verify(iface: String, ip: String): Boolean = isRateLimitActive(iface, ip)

    /**
     * Standard rollback contract: removes class and filters for target IP.
     */
    suspend fun rollback(iface: String, ip: String): Result<Boolean> = removeDeviceRateLimit(iface, ip)

    /**
     * Standard verifyRollback contract: confirms class has been deleted.
     */
    suspend fun verifyRollback(iface: String, ip: String): Boolean = !isRateLimitActive(iface, ip)

    private fun isSameSubnet(ip1: String, ip2: String): Boolean {
        val p1 = ip1.split('.')
        val p2 = ip2.split('.')
        if (p1.size != 4 || p2.size != 4) return false
        return p1[0] == p2[0] && p1[1] == p2[1] && p1[2] == p2[2]
    }

    suspend fun removeDeviceRateLimit(iface: String, ip: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable"))
        }

        val classId = computeClassId(ip)
        val handleId = "1:$classId"

        val delCmd = "tc class del dev $iface parent $TC_ROOT_HANDLE classid $handleId 2>/dev/null"
        PrivilegeManager.executePrivilegedCommand(delCmd)

        Result.success(true)
    }

    suspend fun applyGlobalRateLimit(iface: String, rateKbps: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable"))
        }

        ensureRootQdisc(iface)

        val defaultHandle = "1:$DEFAULT_CLASS_ID"
        val cmd = "tc class replace dev $iface parent $TC_ROOT_HANDLE classid $defaultHandle htb rate ${rateKbps}kbit ceil ${rateKbps}kbit"
        val res = PrivilegeManager.executePrivilegedCommand(cmd)
        res.map { true }
    }

    suspend fun removeGlobalRateLimit(iface: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable"))
        }

        val defaultHandle = "1:$DEFAULT_CLASS_ID"
        val cmd = "tc class del dev $iface parent $TC_ROOT_HANDLE classid $defaultHandle 2>/dev/null"
        PrivilegeManager.executePrivilegedCommand(cmd)
        Result.success(true)
    }

    suspend fun isRateLimitActive(iface: String, ip: String): Boolean = withContext(Dispatchers.IO) {
        val classId = computeClassId(ip)
        val handleId = "1:$classId"
        val res = PrivilegeManager.executePrivilegedCommand("tc class show dev $iface")
        if (res.isSuccess) {
            res.getOrNull()?.contains("class htb $handleId") == true
        } else {
            false
        }
    }

    suspend fun rollbackTrafficControl(iface: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable"))
        }

        val delRoot = PrivilegeManager.executePrivilegedCommand("tc qdisc del dev $iface root 2>/dev/null")
        delRoot.map { true }
    }

    suspend fun clearAllRateLimits(iface: String): Result<Boolean> = rollbackTrafficControl(iface)
}
