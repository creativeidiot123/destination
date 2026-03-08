package com.ankit.destination.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppCatalogCacheTest {
    @Test
    fun getOrLoad_reusesFreshCatalog() {
        var nowMs = 1_000L
        var loadCount = 0
        val cache = InstalledAppCatalogCache(
            ttlMs = 10_000L,
            clock = { nowMs }
        )

        val first = cache.getOrLoad(launchableOnly = true) {
            loadCount += 1
            listOf(InstalledAppCatalogEntry("pkg.one", "One"))
        }
        val second = cache.getOrLoad(launchableOnly = true) {
            loadCount += 1
            listOf(InstalledAppCatalogEntry("pkg.two", "Two"))
        }

        assertEquals(1, loadCount)
        assertEquals(first, second)
    }

    @Test
    fun invalidate_andTtlExpiry_rebuildCatalog() {
        var nowMs = 1_000L
        var loadCount = 0
        val cache = InstalledAppCatalogCache(
            ttlMs = 500L,
            clock = { nowMs }
        )

        cache.getOrLoad(launchableOnly = true) {
            loadCount += 1
            listOf(InstalledAppCatalogEntry("pkg.one", "One"))
        }

        cache.invalidate()

        cache.getOrLoad(launchableOnly = true) {
            loadCount += 1
            listOf(InstalledAppCatalogEntry("pkg.one", "One"))
        }

        nowMs += 1_000L

        cache.getOrLoad(launchableOnly = true) {
            loadCount += 1
            listOf(InstalledAppCatalogEntry("pkg.one", "One"))
        }

        assertEquals(3, loadCount)
    }

    @Test
    fun freshnessCheck_rejectsBackwardsClockAndExpiredEntries() {
        assertTrue(
            InstalledAppCatalogCache.isCacheFresh(
                loadedAtMs = 100L,
                nowMs = 150L,
                ttlMs = 100L
            )
        )
        assertEquals(
            false,
            InstalledAppCatalogCache.isCacheFresh(
                loadedAtMs = 100L,
                nowMs = 250L,
                ttlMs = 100L
            )
        )
        assertEquals(
            false,
            InstalledAppCatalogCache.isCacheFresh(
                loadedAtMs = 200L,
                nowMs = 150L,
                ttlMs = 100L
            )
        )
    }
}
