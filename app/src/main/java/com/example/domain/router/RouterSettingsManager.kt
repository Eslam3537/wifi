package com.example.domain.router

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RouterSettingsManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("router_secure_prefs", Context.MODE_PRIVATE)
    private val keyAlias = "NetManagerRouterKey"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        createKeyIfNeeded()
    }

    private fun createKeyIfNeeded() {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(keyAlias, null) as SecretKey
    }

    fun saveRouterCredentials(username: String, password: String, gatewayIp: String) {
        val cipher = Cipher.getInstance("AES/GCM/先进".replace("先进", "NoPadding"))
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv

        val raw = "$username:$password"
        val encrypted = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString("gateway_ip", gatewayIp)
            .putString("enc_creds", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    fun getRouterCredentials(): Pair<String, String>? {
        val enc = prefs.getString("enc_creds", null) ?: return null
        val ivStr = prefs.getString("iv", null) ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, Base64.decode(ivStr, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val decrypted = cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP))
            val creds = String(decrypted, Charsets.UTF_8).split(":")
            if (creds.size >= 2) Pair(creds[0], creds[1]) else null
        } catch (e: Exception) {
            null
        }
    }

    fun getStoredGatewayIp(): String {
        return prefs.getString("gateway_ip", "192.168.1.1") ?: "192.168.1.1"
    }

    fun getSavedGatewayIp(): String? {
        return prefs.getString("gateway_ip", null)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
