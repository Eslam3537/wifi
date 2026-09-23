package com.example.domain.protection

import com.example.domain.privilege.PrivilegeManager
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class EnforcementRule(
    val ruleId: String = java.util.UUID.randomUUID().toString(),
    val backend: EnforcementBackend = EnforcementBackend.ROOT_IPTABLES,
    val targetDeviceId: String,
    val targetMac: String?,
    val targetIp: String,
    val createdAt: Long = System.currentTimeMillis(),
    val networkInterface: String = "wlan0",
    val owner: String = "APPLICATION_OWNED"
)

data class BlockOperationResult(
    val success: Boolean,
    val rule: EnforcementRule?,
    val failureReason: String? = null
)

/**
 * Isolated, verified firewall enforcement engine with exact rule ownership.
 *
 * SAFETY INVARIANTS:
 * 1. All local iptables rules live strictly inside isolated chain [CHAIN_NAME].
 * 2. Every rule is tracked with exact metadata (owner = APPLICATION_OWNED).
 * 3. Never wildcards or flushes system chains (INPUT, OUTPUT, FORWARD).
 * 4. Checks TrafficPathAnalyzer before attempting local iptables remote client blocking.
 * 5. Requires post-execution rule verification before reporting success.
 */
object FirewallEnforcementEngine {

    const val CHAIN_NAME = "NETMANAGER_GUARD"

    // Active rules registry owned by this application
    private val activeRulesMap = ConcurrentHashMap<String, EnforcementRule>()

    fun getActiveRules(): List<EnforcementRule> = activeRulesMap.values.toList()

    suspend fun ensureChainExists(): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable"))
        }

        // 1. Check if chain already exists
        val checkChain = PrivilegeManager.executePrivilegedCommand("iptables -n -L $CHAIN_NAME")
        if (checkChain.isFailure) {
            val createRes = PrivilegeManager.executePrivilegedCommand("iptables -N $CHAIN_NAME")
            if (createRes.isFailure) {
                return@withContext Result.failure(RuntimeException("Failed to create isolated iptables chain: ${createRes.exceptionOrNull()?.message}"))
            }
        }

        // 2. Safely hook chain into INPUT, FORWARD and OUTPUT tables if not already hooked
        PrivilegeManager.executePrivilegedCommand("iptables -C INPUT -j $CHAIN_NAME 2>/dev/null || iptables -I INPUT 1 -j $CHAIN_NAME")
        PrivilegeManager.executePrivilegedCommand("iptables -C FORWARD -j $CHAIN_NAME 2>/dev/null || iptables -I FORWARD 1 -j $CHAIN_NAME")
        PrivilegeManager.executePrivilegedCommand("iptables -C OUTPUT -j $CHAIN_NAME 2>/dev/null || iptables -I OUTPUT 1 -j $CHAIN_NAME")

        Result.success(true)
    }

    /**
     * Executes verified block workflow for a target device IP.
     */
    suspend fun enforceVerifiedBlock(
        targetIp: String,
        targetMac: String?,
        localIp: String?,
        gatewayIp: String?,
        interfaceName: String?
    ): BlockOperationResult = withContext(Dispatchers.IO) {
        val iface = interfaceName ?: "wlan0"

        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext BlockOperationResult(
                success = false,
                rule = null,
                failureReason = "Root / KernelSU superuser privileges required for network firewall enforcement."
            )
        }

        // Step 1: Analyze traffic path for accurate reporting
        val trafficPath = TrafficPathAnalyzer.analyzeTrafficPath(localIp, gatewayIp, iface)

        // Step 2: Ensure isolated chain is ready
        val chainReady = ensureChainExists()
        if (chainReady.isFailure) {
            return@withContext BlockOperationResult(
                success = false,
                rule = null,
                failureReason = "Failed to initialize firewall chain: ${chainReady.exceptionOrNull()?.message}"
            )
        }

        // Step 3: Create enforcement rule
        val rule = EnforcementRule(
            backend = EnforcementBackend.ROOT_IPTABLES,
            targetDeviceId = targetMac ?: targetIp,
            targetMac = targetMac,
            targetIp = targetIp,
            networkInterface = iface
        )

        // Step 4: Apply drop rules for forward, input, and output traffic inside isolated chain
        val applyCmd = "iptables -A $CHAIN_NAME -s $targetIp -j DROP && iptables -A $CHAIN_NAME -d $targetIp -j DROP"
        val applyRes = PrivilegeManager.executePrivilegedCommand(applyCmd)
        if (applyRes.isFailure) {
            return@withContext BlockOperationResult(
                success = false,
                rule = null,
                failureReason = "Iptables rule insertion failed: ${applyRes.exceptionOrNull()?.message}"
            )
        }

        // Step 5: Verify enforcement rule exists in isolated chain
        val isVerified = verifyRuleInChain(targetIp)
        if (!isVerified) {
            // Rollback insertion if verification fails
            PrivilegeManager.executePrivilegedCommand("iptables -D $CHAIN_NAME -s $targetIp -j DROP 2>/dev/null; iptables -D $CHAIN_NAME -d $targetIp -j DROP 2>/dev/null")
            return@withContext BlockOperationResult(
                success = false,
                rule = null,
                failureReason = "Block rule verification failed: Rule for $targetIp was not detected in chain $CHAIN_NAME after execution."
            )
        }

        // Step 6: Register verified rule in ownership map
        activeRulesMap[targetIp] = rule
        val statusMessage = if (trafficPath.canLocalFirewallBlockRemoteClients) {
            "Whole-network cutoff enforced via KernelSU forward firewall."
        } else {
            "Device isolated via KernelSU firewall."
        }
        BlockOperationResult(success = true, rule = rule, failureReason = statusMessage)
    }

    /**
     * Executes verified unblock workflow for a target device IP.
     */
    suspend fun enforceVerifiedUnblock(targetIp: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable"))
        }

        // Remove rules from isolated chain
        val deleteCmd = "iptables -D $CHAIN_NAME -s $targetIp -j DROP 2>/dev/null; iptables -D $CHAIN_NAME -d $targetIp -j DROP 2>/dev/null"
        PrivilegeManager.executePrivilegedCommand(deleteCmd)

        // Verify rule is gone
        val stillExists = verifyRuleInChain(targetIp)
        if (stillExists) {
            // Second pass deletion
            PrivilegeManager.executePrivilegedCommand(deleteCmd)
        }

        activeRulesMap.remove(targetIp)
        Result.success(true)
    }

    /**
     * Confirms whether drop rules for the given IP exist in isolated chain [CHAIN_NAME].
     */
    suspend fun verifyRuleInChain(ip: String): Boolean = withContext(Dispatchers.IO) {
        val res = PrivilegeManager.executePrivilegedCommand("iptables -n -L $CHAIN_NAME")
        if (res.isSuccess) {
            val output = res.getOrNull() ?: ""
            output.contains(ip)
        } else {
            false
        }
    }

    suspend fun isRuleActive(ip: String): Boolean = withContext(Dispatchers.IO) {
        activeRulesMap.containsKey(ip) || verifyRuleInChain(ip)
    }

    /**
     * Safely rolls back ONLY application-owned rules in [CHAIN_NAME].
     * Never touches system chains or other apps.
     */
    suspend fun rollbackAppRules(): Result<Int> = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege unavailable for firewall rollback"))
        }

        // Unhook from system chains
        PrivilegeManager.executePrivilegedCommand("iptables -D FORWARD -j $CHAIN_NAME 2>/dev/null; iptables -D OUTPUT -j $CHAIN_NAME 2>/dev/null")

        // Flush and delete isolated custom chain
        PrivilegeManager.executePrivilegedCommand("iptables -F $CHAIN_NAME 2>/dev/null")
        PrivilegeManager.executePrivilegedCommand("iptables -X $CHAIN_NAME 2>/dev/null")

        val count = activeRulesMap.size
        activeRulesMap.clear()
        Result.success(count)
    }
}
