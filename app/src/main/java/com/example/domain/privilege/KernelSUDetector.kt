package com.example.domain.privilege

import com.example.model.KernelSuStatus
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class KernelSuAuditReport(
    val status: KernelSuStatus,
    val isKernelSuDetected: Boolean,
    val isRootGrantedThroughKsu: Boolean,
    val detectionEvidence: List<String>,
    val versionString: String? = null
)

object KernelSUDetector {

    private val KSU_STATIC_PATHS = listOf(
        "/system/bin/ksud",
        "/vendor/bin/ksud"
    )

    /**
     * Comprehensive multi-indicator detection for KernelSU / KernelSU Next.
     * Avoids false negatives when `/system/bin/ksud` is omitted in GKI environments.
     */
    suspend fun auditKernelSu(rootReport: RootAuditReport): KernelSuAuditReport = withContext(Dispatchers.IO) {
        val evidence = mutableListOf<String>()
        var versionString: String? = null
        var isKsuDetected = false

        // Indicator 1: Check /proc/version for KernelSU marker in kernel banner
        try {
            val procVersion = File("/proc/version")
            if (procVersion.exists() && procVersion.canRead()) {
                val banner = procVersion.readText()
                if (banner.contains("KernelSU", ignoreCase = true) || banner.contains("-ksu", ignoreCase = true)) {
                    isKsuDetected = true
                    evidence.add("Kernel banner contains KernelSU signature: ${banner.take(60)}...")
                }
            }
        } catch (_: Exception) {}

        // Indicator 2: Check for KernelSU character device node /dev/ksu (only if root available to prevent SELinux audit denials)
        if (rootReport.status == RootStatus.ROOT_AVAILABLE) {
            try {
                val ksuDev = File("/dev/ksu")
                if (ksuDev.exists()) {
                    isKsuDetected = true
                    evidence.add("KernelSU character device /dev/ksu is present")
                }
            } catch (_: Exception) {}

            // Indicator 3: Static filesystem locations (safe paths only)
            for (path in KSU_STATIC_PATHS) {
                try {
                    val f = File(path)
                    if (f.exists()) {
                        isKsuDetected = true
                        evidence.add("KernelSU filesystem path located: $path")
                    }
                } catch (_: Exception) {}
            }
        }

        // Indicator 4: Check `su -v` output ONLY if root is available
        if (rootReport.status == RootStatus.ROOT_AVAILABLE && rootReport.binaryPath != null) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(rootReport.binaryPath, "-v"))
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                if (out.contains("KernelSU", ignoreCase = true) || out.contains("ksu", ignoreCase = true)) {
                    isKsuDetected = true
                    versionString = out
                    evidence.add("`su -v` reports KernelSU: $out")
                }
            } catch (_: Exception) {}
        }

        // Indicator 5: If Root is available, query privileged environment for ksud
        if (rootReport.status == RootStatus.ROOT_AVAILABLE) {
            val cmdRes = PrivilegeManager.executePrivilegedCommand("which ksud || /data/adb/ksu/bin/ksud -V || /system/bin/ksud -V")
            val output = cmdRes.getOrNull()?.trim()
            if (!output.isNullOrBlank() && !output.contains("not found") && !output.contains("No such file")) {
                isKsuDetected = true
                if (versionString == null) versionString = output
                evidence.add("Privileged ksud binary response: $output")
            }

            // Check privileged /proc/version if unprivileged read was restricted by SELinux
            if (!isKsuDetected) {
                val procRes = PrivilegeManager.executePrivilegedCommand("cat /proc/version")
                val privBanner = procRes.getOrNull() ?: ""
                if (privBanner.contains("KernelSU", ignoreCase = true) || privBanner.contains("-ksu", ignoreCase = true)) {
                    isKsuDetected = true
                    evidence.add("Privileged /proc/version confirms KernelSU kernel")
                }
            }
        }

        val isRootGranted = rootReport.status == RootStatus.ROOT_AVAILABLE && isKsuDetected

        val status = when {
            isRootGranted -> KernelSuStatus.SUPPORTED_AND_VERIFIED
            isKsuDetected -> KernelSuStatus.DETECTED_NO_PERMISSION
            else -> KernelSuStatus.NOT_DETECTED
        }

        KernelSuAuditReport(
            status = status,
            isKernelSuDetected = isKsuDetected,
            isRootGrantedThroughKsu = isRootGranted,
            detectionEvidence = evidence,
            versionString = versionString
        )
    }
}
