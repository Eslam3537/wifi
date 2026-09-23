package com.example.domain.security

import android.content.Context
import android.content.SharedPreferences

class ProtectedDevicesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("protected_devices_prefs", Context.MODE_PRIVATE)

    fun isProtected(ip: String): Boolean {
        return prefs.getBoolean(KEY_PREFIX + ip, false)
    }

    fun setProtected(ip: String, isProtected: Boolean) {
        prefs.edit().putBoolean(KEY_PREFIX + ip, isProtected).apply()
    }

    fun getAllProtectedIps(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(KEY_PREFIX) }
            .toSet()
    }

    companion object {
        private const val KEY_PREFIX = "protected_ip_"
    }
}
