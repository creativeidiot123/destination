package com.ankit.destination

import com.ankit.destination.enforce.EnforcementExecutor
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementExecutorTest {

    @Test
    fun executeLatest_coalescesQueuedUpdatesIntoLatestTaskOnly() {
        val key = "coalesce-${System.nanoTime()}"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val executed = Collections.synchronizedList(mutableListOf<Int>())

        val firstScheduled = EnforcementExecutor.executeLatest(key) {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            executed += 1
            completed.countDown()
        }
        assertTrue(firstScheduled)
        assertTrue(started.await(1, TimeUnit.SECONDS))

        val secondScheduled = EnforcementExecutor.executeLatest(key) {
            executed += 2
            completed.countDown()
        }
        val thirdScheduled = EnforcementExecutor.executeLatest(key) {
            executed += 3
            completed.countDown()
        }

        assertFalse(secondScheduled)
        assertFalse(thirdScheduled)

        release.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(listOf(1, 3), executed.toList())
    }

    @Test
    fun executeLatest_invokesDroppedCallback_forSupersededQueuedTask() {
        val key = "drop-${System.nanoTime()}"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val executed = Collections.synchronizedList(mutableListOf<Int>())
        val dropped = Collections.synchronizedList(mutableListOf<Int>())

        EnforcementExecutor.executeLatest(key) {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            executed += 1
            completed.countDown()
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))

        EnforcementExecutor.executeLatest(
            key = key,
            onDropped = { dropped += 2 }
        ) {
            executed += 2
            completed.countDown()
        }
        EnforcementExecutor.executeLatest(
            key = key,
            onDropped = { dropped += 3 }
        ) {
            executed += 3
            completed.countDown()
        }

        release.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(listOf(1, 3), executed.toList())
        assertEquals(listOf(2), dropped.toList())
    }
}
