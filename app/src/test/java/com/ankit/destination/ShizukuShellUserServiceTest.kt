package com.ankit.destination

import com.ankit.destination.provisioning.ShizukuShellUserService
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellUserServiceTest {
    @Test
    fun destroyProcess_returnsTrue_whenProcessStopsAfterDestroy() {
        val process = FakeProcess(waitForAfterDestroy = true)

        val destroyed = ShizukuShellUserService.destroyProcess(process, destroyGraceMs = 100L)

        assertTrue(destroyed)
        assertTrue(process.destroyCalled)
        assertFalse(process.destroyForciblyCalled)
    }

    @Test
    fun destroyProcess_forcesStop_whenProcessIgnoresDestroy() {
        val process = FakeProcess(waitForAfterDestroy = false)

        val destroyed = ShizukuShellUserService.destroyProcess(process, destroyGraceMs = 100L)

        assertTrue(destroyed)
        assertTrue(process.destroyCalled)
        assertTrue(process.destroyForciblyCalled)
    }

    @Test
    fun waitForProcessExit_usesProcessTimeoutApi() {
        val process = FakeProcess(waitForAfterDestroy = true, waitForResult = true)

        val exited = ShizukuShellUserService.waitForProcessExit(process, timeoutMs = 321L)

        assertTrue(exited)
        assertEquals(321L, process.lastWaitForTimeoutMs)
        assertEquals(TimeUnit.MILLISECONDS, process.lastWaitForUnit)
    }

    @Test
    fun waitForProcessExit_returnsFalse_whenInterrupted() {
        val process = FakeProcess(waitForAfterDestroy = true, interruptOnWait = true)

        val exited = ShizukuShellUserService.waitForProcessExit(process, timeoutMs = 321L)

        assertFalse(exited)
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()
    }

    private class FakeProcess(
        private val waitForAfterDestroy: Boolean,
        private val waitForResult: Boolean = false,
        private val interruptOnWait: Boolean = false
    ) : Process() {
        var destroyCalled: Boolean = false
        var destroyForciblyCalled: Boolean = false
        var lastWaitForTimeoutMs: Long? = null
        var lastWaitForUnit: TimeUnit? = null

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            lastWaitForTimeoutMs = timeout
            lastWaitForUnit = unit
            if (interruptOnWait) {
                throw InterruptedException("interrupted")
            }
            return waitForResult || (destroyCalled && (waitForAfterDestroy || destroyForciblyCalled))
        }

        override fun exitValue(): Int {
            if (!destroyCalled || (!waitForAfterDestroy && !destroyForciblyCalled)) {
                throw IllegalThreadStateException("Process still running")
            }
            return 0
        }

        override fun destroy() {
            destroyCalled = true
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            destroyCalled = true
            return this
        }

        override fun isAlive(): Boolean = !destroyCalled || (!waitForAfterDestroy && !destroyForciblyCalled)
    }
}
