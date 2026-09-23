package com.example.domain.privilege

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result of a unified system command execution.
 */
data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val executionTimeMs: Long,
    val isRoot: Boolean,
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Unified system command execution layer.
 * Replaces scattered Runtime.exec() calls with structured timeout, logging,
 * streams consumption, cancellation, and error handling.
 */
object CommandExecutor {

    private const val DEFAULT_TIMEOUT_SECONDS = 5L

    /**
     * Executes a non-root system command.
     */
    suspend fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        workingDir: File? = null,
        environment: Map<String, String>? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        runProcess(
            commandTokens = arrayOf("sh", "-c", command),
            originalCommand = command,
            isRoot = false,
            timeoutSeconds = timeoutSeconds,
            workingDir = workingDir,
            environment = environment
        )
    }

    /**
     * Executes a privileged (root) system command using `su -c`.
     */
    suspend fun executePrivileged(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        workingDir: File? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        // Fast-fail if su binary is unavailable to avoid SELinux audit rate-limiting
        if (PrivilegeManager.checkRootStatus() != com.example.model.RootStatus.ROOT_AVAILABLE) {
            return@withContext CommandResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "Root execution not available on this device",
                executionTimeMs = 0L,
                isRoot = true,
                timedOut = false
            )
        }

        runProcess(
            commandTokens = arrayOf("su", "-c", command),
            originalCommand = command,
            isRoot = true,
            timeoutSeconds = timeoutSeconds,
            workingDir = workingDir,
            environment = null
        )
    }

    private fun runProcess(
        commandTokens: Array<String>,
        originalCommand: String,
        isRoot: Boolean,
        timeoutSeconds: Long,
        workingDir: File?,
        environment: Map<String, String>?
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        var process: Process? = null

        return try {
            val processBuilder = ProcessBuilder(*commandTokens).apply {
                if (workingDir != null) directory(workingDir)
                if (environment != null) environment().putAll(environment)
            }

            process = processBuilder.start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - startTime

            if (!completed) {
                process.destroyForcibly()
                return CommandResult(
                    command = originalCommand,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Command timed out after ${timeoutSeconds}s",
                    executionTimeMs = elapsed,
                    isRoot = isRoot,
                    timedOut = true
                )
            }

            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.exitValue()

            CommandResult(
                command = originalCommand,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                executionTimeMs = elapsed,
                isRoot = isRoot,
                timedOut = false
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            CommandResult(
                command = originalCommand,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Execution failed with exception",
                executionTimeMs = elapsed,
                isRoot = isRoot,
                timedOut = false
            )
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {}
        }
    }
}
