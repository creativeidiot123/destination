package com.ankit.destination.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

private const val INSTALLED_APP_CATALOG_TTL_MS = 30_000L

internal data class InstalledAppCatalogEntry(
    val packageName: String,
    val label: String
)

internal class InstalledAppCatalogCache(
    private val ttlMs: Long = INSTALLED_APP_CATALOG_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val entriesByMode = mutableMapOf<Boolean, CachedCatalog>()

    fun getOrLoad(
        launchableOnly: Boolean,
        loader: () -> List<InstalledAppCatalogEntry>
    ): List<InstalledAppCatalogEntry> {
        synchronized(lock) {
            entriesByMode[launchableOnly]
                ?.takeIf { isCacheFresh(it.loadedAtMs, clock(), ttlMs) }
                ?.let { return it.entries }
        }

        val loaded = loader()
        val loadedAtMs = clock()

        synchronized(lock) {
            entriesByMode[launchableOnly]
                ?.takeIf { isCacheFresh(it.loadedAtMs, clock(), ttlMs) }
                ?.let { return it.entries }

            entriesByMode[launchableOnly] = CachedCatalog(
                entries = loaded,
                loadedAtMs = loadedAtMs
            )
            return loaded
        }
    }

    fun invalidate() {
        synchronized(lock) {
            entriesByMode.clear()
        }
    }

    companion object {
        internal fun isCacheFresh(
            loadedAtMs: Long,
            nowMs: Long,
            ttlMs: Long
        ): Boolean {
            if (loadedAtMs <= 0L || ttlMs <= 0L) return false
            val ageMs = nowMs - loadedAtMs
            if (ageMs < 0L) return false
            return ageMs < ttlMs
        }
    }

    private data class CachedCatalog(
        val entries: List<InstalledAppCatalogEntry>,
        val loadedAtMs: Long
    )
}

internal object SharedInstalledAppCatalogCache {
    private val cache = InstalledAppCatalogCache()

    fun getCatalog(
        context: Context,
        launchableOnly: Boolean
    ): List<InstalledAppCatalogEntry> {
        val appContext = context.applicationContext
        return cache.getOrLoad(launchableOnly) {
            loadInstalledAppCatalog(
                context = appContext,
                launchableOnly = launchableOnly
            )
        }
    }

    fun invalidate() {
        cache.invalidate()
    }
}

internal fun invalidateInstalledAppOptionsCache() {
    SharedInstalledAppCatalogCache.invalidate()
}

internal fun loadInstalledAppCatalog(
    context: Context,
    launchableOnly: Boolean
): List<InstalledAppCatalogEntry> {
    val packageManager = context.packageManager
    val packages = linkedSetOf<String>()
    if (launchableOnly) {
        val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            packages += context.packageName
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapTo(packages) { it.activityInfo.packageName }
    } else {
        packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
            .mapTo(packages) { it.packageName }
    }

    return packages.map { packageName ->
        resolveInstalledAppCatalogEntry(
            packageManager = packageManager,
            packageName = packageName
        )
    }
}

internal fun resolveInstalledAppCatalogEntry(
    packageManager: PackageManager,
    packageName: String
): InstalledAppCatalogEntry {
    val label = runCatching {
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(appInfo).toString()
    }.getOrDefault(packageName)

    return InstalledAppCatalogEntry(
        packageName = packageName,
        label = label
    )
}
