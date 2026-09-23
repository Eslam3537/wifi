package com.example.domain.privilege

import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class RootExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isUid0: Boolean
)

data class RootAuditReport(
    val status: RootStatus,
    val isUid0Verified: Boolean,
    val binaryPath: String?,
    val executionResult: RootExecutionResult?,
    val failureReason: String? = null
)

object RootCapabilityDetector {

    private val STANDARD_SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su"
    )

    /**
     * Executes `su -c id` and strictly verifies `uid=0`.
     * Captures exitCode, stdout, stderr with safety timeouts.
     */
    suspend fun auditRootCapability(): RootAuditReport = withContext(Dispatchers.IO) {
        val detectedBinary = findSuBinary()

        if (detectedBinary == null) {
            return@withContext RootAuditReport(
                status = RootStatus.NO_ROOT,
                isUid0Verified = false,
                binaryPath = null,
                executionResult = null,
                failureReason = "No executable su binary found in system paths"
            )
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf(detectedBinary, "-c", "id"))
            val completed = process.waitFor(4, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return@withContext RootAuditReport(
                    status = RootStatus.ROOT_DENIED,
                    isUid0Verified = false,
                    binaryPath = detectedBinary,
                    executionResult = null,
                    failureReason = "Root request timed out (permission dialog likely ignored or denied)"
                )
            }

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.exitValue()

            val isUid0 = exitCode == 0 && (stdout.contains("uid=0(root)") || stdout.contains("uid=0"))

            val status = when {
                isUid0 -> RootStatus.ROOT_AVAILABLE
                exitCode == 0 -> RootStatus.ROOT_DENIED
                else -> RootStatus.ROOT_DENIED
            }

            RootAuditReport(
                status = status,
                isUid0Verified = isUid0,
                binaryPath = detectedBinary,
                executionResult = RootExecutionResult(exitCode, stdout, stderr, isUid0),
                failureReason = if (!isUid0) "Execution returned code $exitCode: $stderr" else null
            )
        } catch (e: Exception) {
            RootAuditReport(
                status = RootStatus.ROOT_DENIED,
                isUid0Verified = false,
                binaryPath = detectedBinary,
                executionResult = null,
                failureReason = e.message
            )
        }
    }

    private fun findSuBinary(): String? {
        for (path in STANDARD_SU_PATHS) {
            try {
                val f = File(path)
                if (f.exists() && f.canExecute()) return path
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }

        val pathDirs = System.getenv("PATH")?.split(":") ?: emptyList()
        for (dir in pathDirs) {
            if (dir.isBlank() || dir.startsWith("/data") || dir.startsWith("/sbin") || dir.startsWith("/vendor")) continue
            try {
                val f = File(dir, "su")
                if (f.exists() && f.canExecute()) return f.absolutePath
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }
        return null
    }
}
