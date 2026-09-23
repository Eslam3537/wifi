package com.example.domain.privilege

import com.example.model.KernelSuStatus
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object PrivilegeManager {

    private var cachedRootReport: RootAuditReport? = null
    private var cachedKsuReport: KernelSuAuditReport? = null
    private var lastAuditTime: Long = 0L
    private const val CACHE_TTL_MS = 15_000L // 15 seconds cache to prevent storming audits

    suspend fun auditRoot(forceRefresh: Boolean = false): RootAuditReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedRootReport != null && (now - lastAuditTime < CACHE_TTL_MS)) {
            return@withContext cachedRootReport!!
        }
        val report = RootCapabilityDetector.auditRootCapability()
        cachedRootReport = report
        lastAuditTime = now
        report
    }

    suspend fun auditKernelSu(forceRefresh: Boolean = false): KernelSuAuditReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedKsuReport != null && (now - lastAuditTime < CACHE_TTL_MS)) {
            return@withContext cachedKsuReport!!
        }
        val rootReport = auditRoot(forceRefresh)
        val ksuReport = KernelSUDetector.auditKernelSu(rootReport)
        cachedKsuReport = ksuReport
        ksuReport
    }

    suspend fun checkRootStatus(forceRefresh: Boolean = false): RootStatus = auditRoot(forceRefresh).status

    suspend fun checkKernelSuStatus(forceRefresh: Boolean = false): KernelSuStatus = auditKernelSu(forceRefresh).status

    /**
     * Executes a privileged shell command with a safety timeout and sanitized stream handling.
     * Prevents spawning `su` if root is not available to avoid SELinux audit denials.
     */
    suspend fun executePrivilegedCommand(cmd: String, timeoutSeconds: Long = 5): Result<String> = withContext(Dispatchers.IO) {
        val rootStatus = checkRootStatus()
        if (rootStatus != RootStatus.ROOT_AVAILABLE) {
            return@withContext Result.failure(IllegalStateException("Root privilege is unavailable on this device ($rootStatus)"))
        }
        val result = CommandExecutor.executePrivileged(cmd, timeoutSeconds)
        if (result.isSuccess) {
            Result.success(result.stdout)
        } else {
            Result.failure(RuntimeException("Command failed (exit ${result.exitCode}): ${result.stderr}"))
        }
    }
}
