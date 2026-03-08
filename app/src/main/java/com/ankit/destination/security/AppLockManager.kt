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

    fun isProtectionEnabled(): Boolean {
        return isPasswordSet() && prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun enableProtection(): Boolean {
        if (!isPasswordSet()) return false
        return prefs.edit()
            .putBoolean(KEY_PROTECTION_ENABLED, true)
            .commit()
    }

    fun createPasswordAndEnableProtection(password: String): Boolean {
        if (password.length < MIN_PASSWORD_LEN) return false
        val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashPassword(password, salt)
        return prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(KEY_PROTECTION_ENABLED, true)
            .commit()
    }

    fun disableProtection(password: String): Boolean {
        if (!verifyPassword(password)) return false
        return prefs.edit()
            .putBoolean(KEY_PROTECTION_ENABLED, false)
            .commit()
    }

    fun verifyPassword(password: String): Boolean {
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

    fun startAdminSession() {
        prefs.edit().putLong(KEY_SESSION_EXPIRY, System.currentTimeMillis() + SESSION_DURATION_MS).apply()
    }

    fun isAdminSessionActive(): Boolean {
        if (!isProtectionEnabled()) return true
        return System.currentTimeMillis() < prefs.getLong(KEY_SESSION_EXPIRY, 0L)
    }

    fun getSessionRemainingMs(): Long {
        return (prefs.getLong(KEY_SESSION_EXPIRY, 0L) - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun endAdminSession() {
        prefs.edit().remove(KEY_SESSION_EXPIRY).apply()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "admin_lock_store"
        private const val KEY_HASH = "hash"
        private const val KEY_SALT = "salt"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
        private const val MIN_PASSWORD_LEN = 4
        private const val ITERATIONS = 120_000
        private const val KEY_BITS = 256
        private const val SALT_SIZE_BYTES = 16
        private val secureRandom = SecureRandom()
        private const val KEY_SESSION_EXPIRY = "admin_session_expiry"
        private const val SESSION_DURATION_MS = 5 * 60 * 1000L
    }
}
