package com.example.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-device pairing tokens issued by the Mac after a successful PIN pairing.
 *
 * Tokens are encrypted with an AES-256-GCM key that never leaves the Android Keystore,
 * so a backup or a rooted-device file dump does not expose them in clear text.
 *
 * Keys are stored per Mac address (the host the phone connected to).
 */
object PairingStore {
    private const val TAG = "PairingStore"
    private const val PREFS = "miccast_pairing"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "miccast_pairing_key"
    private const val TOKEN_PREFIX = "token:"

    /** Stable identity of this phone, created once and reused for every Mac. */
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun deviceName(): String = (android.os.Build.MODEL ?: "Android").take(64)

    fun token(context: Context, macKey: String): ByteArray? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_PREFIX + macKey, null) ?: return null

        return runCatching { decrypt(stored) }
            .onFailure { Log.w(TAG, "Could not decrypt token for $macKey: ${it.message}") }
            .getOrNull()
    }

    fun saveToken(context: Context, macKey: String, token: ByteArray) {
        runCatching { encrypt(token) }
            .onSuccess {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(TOKEN_PREFIX + macKey, it)
                    .apply()
            }
            .onFailure { Log.e(TAG, "Could not store token: ${it.message}") }
    }

    fun clearToken(context: Context, macKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(TOKEN_PREFIX + macKey)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): ByteArray {
        val parts = stored.split(":", limit = 2)
        require(parts.size == 2) { "Malformed token blob" }

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
