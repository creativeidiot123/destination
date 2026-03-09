package com.ankit.destination.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAppIconLoaderTest {

    @Test
    fun load_returnsCachedValueWithoutReloading() = runTest {
        val store = FakeIconStore<String>()
        val repository = DeduplicatingIconRepository(
            store = store,
            failureTtlMs = 60_000L,
            maxFailureEntries = 8,
            clock = { 1_000L }
        )
        var loadCount = 0

        val first = repository.load("pkg.alpha") {
            loadCount += 1
            IconLoadResult.Success("icon-a")
        }
        val second = repository.load("pkg.alpha") {
            loadCount += 1
            IconLoadResult.Success("icon-b")
        }

        assertEquals("icon-a", first)
        assertEquals("icon-a", second)
        assertEquals(1, loadCount)
    }

    @Test
    fun load_skipsRepeatedMissingEntriesUntilFailureTtlExpires() = runTest {
        var nowMs = 1_000L
        val repository = DeduplicatingIconRepository(
            store = FakeIconStore<String>(),
            failureTtlMs = 5_000L,
            maxFailureEntries = 8,
            clock = { nowMs }
        )
        var loadCount = 0

        val first = repository.load("pkg.missing") {
            loadCount += 1
            IconLoadResult.Missing
        }
        val second = repository.load("pkg.missing") {
            loadCount += 1
            IconLoadResult.Success("icon")
        }

        nowMs += 6_000L

        val third = repository.load("pkg.missing") {
            loadCount += 1
            IconLoadResult.Success("icon")
        }

        assertNull(first)
        assertNull(second)
        assertEquals("icon", third)
        assertEquals(2, loadCount)
    }

    @Test
    fun load_deduplicatesConcurrentRequestsForSamePackage() = runTest {
        val repository = DeduplicatingIconRepository(
            store = FakeIconStore<String>(),
            failureTtlMs = 60_000L,
            maxFailureEntries = 8,
            clock = { 1_000L }
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        var loadCount = 0

        val first = async(dispatcher) {
            repository.load("pkg.shared") {
                loadCount += 1
                gate.await()
                IconLoadResult.Success("icon")
            }
        }
        val second = async(dispatcher) {
            repository.load("pkg.shared") {
                loadCount += 1
                IconLoadResult.Success("other")
            }
        }

        advanceUntilIdle()
        assertEquals(1, loadCount)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("icon", first.await())
        assertEquals("icon", second.await())
        assertEquals(1, loadCount)
    }

    @Test
    fun load_doesNotCacheCancellationAsFailure() = runTest {
        val repository = DeduplicatingIconRepository(
            store = FakeIconStore<String>(),
            failureTtlMs = 60_000L,
            maxFailureEntries = 8,
            clock = { 1_000L }
        )
        var loadCount = 0

        val firstFailure = runCatching {
            repository.load("pkg.cancelled") {
                loadCount += 1
                throw CancellationException("load cancelled")
            }
        }.exceptionOrNull()

        val second = repository.load("pkg.cancelled") {
            loadCount += 1
            IconLoadResult.Success("icon")
        }

        assertTrue(firstFailure is CancellationException)
        assertEquals("icon", second)
        assertEquals(2, loadCount)
    }

    private class FakeIconStore<T> : IconStore<T> {
        private val values = linkedMapOf<String, T>()

        override fun get(key: String): T? = values[key]

        override fun put(key: String, value: T) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun clear() {
            values.clear()
        }
    }
}
