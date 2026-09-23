package com.example.domain.privilege

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionManager {

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasWifiPermissions(context: Context): Boolean {
        val hasWifiState = hasPermission(context, Manifest.permission.ACCESS_WIFI_STATE)
        val hasFineLoc = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        return hasWifiState && hasFineLoc
    }

    fun hasMulticastPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun getRequiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    fun getRuntimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    fun getMissingRuntimePermissions(context: Context): Array<String> {
        return getRuntimePermissions().filter {
            !hasPermission(context, it)
        }.toTypedArray()
    }

    fun areAllRuntimePermissionsGranted(context: Context): Boolean {
        return getMissingRuntimePermissions(context).isEmpty()
    }
}
