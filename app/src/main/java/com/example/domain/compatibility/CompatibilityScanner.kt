package com.example.domain.compatibility

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.example.domain.privilege.PermissionManager
import com.example.domain.privilege.PrivilegeManager
import com.example.model.CompatibilityAuditResult
import com.example.model.KernelSuStatus
import com.example.model.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CompatibilityScanner {

    suspend fun auditDevice(context: Context): CompatibilityAuditResult = withContext(Dispatchers.IO) {
        val rootStatus = PrivilegeManager.checkRootStatus()
        val ksuStatus = PrivilegeManager.checkKernelSuStatus()

        // Android 10+ restricts MAC addresses to 02:00:00:00:00:00
        val macRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // Android 10+ restricts /proc/net/arp access to SELinux untrusted_app domain
        val procNetArpRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            try {
                val file = File("/proc/net/arp")
                !file.canRead() || file.readLines().size <= 1
            } catch (e: Exception) {
                true
            }
        }

        // Test MulticastLock capability for mDNS / NSD
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLockSupported = try {
            val lock = wifiManager?.createMulticastLock("AuditLock")
            lock != null
        } catch (e: Exception) {
            false
        }

        // Check power save / background restriction
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val backgroundRestricted = !isIgnoringBatteryOptimizations

        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) }

        val detectedDevice = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${Build.HARDWARE})"
        val osName = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        CompatibilityAuditResult(
            targetDevice = detectedDevice,
            osVersion = osName,
            apiLevel = Build.VERSION.SDK_INT,
            isArm64 = isArm64,
            macAddressRestricted = macRestricted,
            procNetArpRestricted = procNetArpRestricted,
            localNetworkMulticastSupported = multicastLockSupported,
            backgroundExecutionRestricted = backgroundRestricted,
            notificationsGranted = PermissionManager.hasNotificationPermission(context),
            wifiStateGranted = PermissionManager.hasPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE),
            fineLocationGranted = PermissionManager.hasPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION),
            rootStatus = rootStatus,
            kernelSuStatus = ksuStatus
        )
    }
}
