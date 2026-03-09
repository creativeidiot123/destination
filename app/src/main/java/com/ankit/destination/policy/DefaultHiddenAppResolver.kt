package com.ankit.destination.policy

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.provider.Settings

internal class DefaultHiddenAppResolver(
    private val context: Context
) {
    private val packageManager: PackageManager = context.packageManager

    fun resolveLockedHiddenPackages(): Set<String> {
        return linkedSetOf<String>().apply {
            add(context.packageName)
            addIfInstalled(this, PLAY_STORE_PACKAGE)
            resolveSettingsPackages().forEach { addIfInstalled(this, it) }
            addIfInstalled(this, GOOGLE_MESSAGES_PACKAGE)
            addIfInstalled(this, GOOGLE_DIALER_PACKAGE)
        }
    }

    private fun resolveSettingsPackages(): Set<String> {
        return resolveIntentPackages(android.content.Intent(Settings.ACTION_SETTINGS))
            .ifEmpty { setOf("com.android.settings") }
    }

    private fun resolveIntentPackages(intent: android.content.Intent): Set<String> {
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName?.trim() }
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun addIfInstalled(target: MutableSet<String>, packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isNotBlank() && isInstalled(normalized)) {
            target += normalized
        }
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private companion object {
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
        private const val GOOGLE_DIALER_PACKAGE = "com.google.android.dialer"
    }
}
