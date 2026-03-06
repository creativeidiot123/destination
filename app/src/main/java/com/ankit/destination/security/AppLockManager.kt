package com.ankit.destination.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AppLockManager(context: Context) {
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPasswordSet(): Boolean {
        return !prefs.getString(KEY_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_SALT, null).isNullOrBlank()
    }

    fun isSessionUnlocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = prefs.getLong(KEY_UNLOCKED_UNTIL, 0L)
        return until > nowMs
    }

    fun unlock(password: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!verifyPassword(password)) return false
        prefs.edit().putLong(KEY_UNLOCKED_UNTIL, nowMs + SESSION_MS).apply()
        return true
    }

    fun setPassword(password: String): Boolean {
        if (password.length < MIN_PASSWORD_LEN) return false
        val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashPassword(password, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putLong(KEY_UNLOCKED_UNTIL, System.currentTimeMillis() + SESSION_MS)
            .apply()
        return true
    }

    fun clearSession() {
        prefs.edit().putLong(KEY_UNLOCKED_UNTIL, 0L).apply()
    }

    private fun verifyPassword(password: String): Boolean {
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val hashB64 = prefs.getString(KEY_HASH, null) ?: return false
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(hashB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = hashPassword(password, salt)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun hashPassword(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private const val PREFS_NAME = "admin_lock_store"
        private const val KEY_HASH = "hash"
        private const val KEY_SALT = "salt"
        private const val KEY_UNLOCKED_UNTIL = "unlocked_until"
        private const val SESSION_MS = 5 * 60_000L
        private const val MIN_PASSWORD_LEN = 4
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val SALT_SIZE_BYTES = 16
        private val secureRandom = SecureRandom()
    }
}
