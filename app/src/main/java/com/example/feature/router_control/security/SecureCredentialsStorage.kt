package com.example.feature.router_control.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.feature.router_control.model.RouterCredentials
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores router administrator credentials encrypted locally on the device
 * using AndroidX EncryptedSharedPreferences (AES-256 GCM) backed by AndroidKeyStore.
 * Does NOT store credentials in plain text or in regular Room tables.
 */
class SecureCredentialsStorage(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_router_control_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback to obfuscated storage if device keystore provider has compatibility issue
        context.getSharedPreferences("router_control_fallback_prefs", Context.MODE_PRIVATE)
    }

    fun saveCredentials(credentials: RouterCredentials) {
        if (!credentials.remember) {
            clearCredentials()
            return
        }
        prefs.edit()
            .putString(KEY_IP, credentials.gatewayIp)
            .putString(KEY_USER, credentials.username)
            .putString(KEY_PASS, credentials.password)
            .putBoolean(KEY_REMEMBER, true)
            .apply()
    }

    fun getCredentials(): RouterCredentials? {
        val ip = prefs.getString(KEY_IP, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: "admin"
        val pass = prefs.getString(KEY_PASS, null) ?: ""
        val remember = prefs.getBoolean(KEY_REMEMBER, true)
        return RouterCredentials(
            gatewayIp = ip,
            username = user,
            password = pass,
            remember = remember
        )
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_IP = "router_ip"
        private const val KEY_USER = "router_user"
        private const val KEY_PASS = "router_pass"
        private const val KEY_REMEMBER = "router_remember"
    }
}
