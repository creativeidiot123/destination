package com.ankit.destination.provisioning

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ShizukuShellUserService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val streamExecutor = Executors.newFixedThreadPool(2)
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what != MSG_RUN_COMMAND) {
                return@Handler false
            }
            val command = message.data.getString(KEY_COMMAND).orEmpty()
            val replyTo = message.replyTo ?: return@Handler true
            executor.execute {
                val result = executeCommand(command)
                val reply = Message.obtain(null, MSG_COMMAND_RESULT).apply {
                    data = Bundle().apply {
                        putString(KEY_COMMAND, command)
                        putInt(KEY_EXIT_CODE, result.exitCode)
                        putString(KEY_STDOUT, result.stdout)
                        putString(KEY_STDERR, result.stderr)
                    }
                }
                runCatching { replyTo.send(reply) }
            }
            true
        }
    )

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        streamExecutor.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun executeCommand(command: String): ProcessExecutionResult {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        return try {
            val stdoutFuture = streamExecutor.submit<String> {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            }
            val stderrFuture = streamExecutor.submit<String> {
                process.errorStream.bufferedReader().use { it.readText() }.trim()
            }
            val finished = waitForProcessExit(process, COMMAND_TIMEOUT_MS)
            if (!finished) {
                destroyProcess(process)
                val stderr = stderrFuture.awaitValueOrDefault()
                return ProcessExecutionResult(
                    exitCode = EXIT_CODE_TIMEOUT,
                    stdout = stdoutFuture.awaitValueOrDefault(),
                    stderr = buildString {
                        append("Command timed out after ${COMMAND_TIMEOUT_MS} ms.")
                        if (stderr.isNotBlank()) {
                            append('\n')
                            append(stderr)
                        }
                    }
                )
            }
            ProcessExecutionResult(
                exitCode = process.exitValue(),
                stdout = stdoutFuture.awaitValueOrDefault(),
                stderr = stderrFuture.awaitValueOrDefault()
            )
        } finally {
            destroyProcess(process)
        }
    }

    private fun Future<String>.awaitValueOrDefault(): String {
        return try {
            get(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: CancellationException) {
            ""
        } catch (_: ExecutionException) {
            ""
        } catch (_: TimeoutException) {
            cancel(true)
            ""
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            cancel(true)
            ""
        }
    }

    private data class ProcessExecutionResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    companion object {
        const val MSG_RUN_COMMAND: Int = 1
        const val MSG_COMMAND_RESULT: Int = 2
        const val KEY_COMMAND: String = "command"
        const val KEY_EXIT_CODE: String = "exit_code"
        const val KEY_STDOUT: String = "stdout"
        const val KEY_STDERR: String = "stderr"

        internal const val COMMAND_TIMEOUT_MS: Long = 10_000L
        internal const val STREAM_READ_TIMEOUT_MS: Long = 1_000L
        internal const val PROCESS_DESTROY_GRACE_MS: Long = 1_000L
        internal const val EXIT_CODE_TIMEOUT: Int = 124

        internal fun destroyProcess(process: Process, destroyGraceMs: Long = PROCESS_DESTROY_GRACE_MS): Boolean {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            process.destroy()
            if (waitForProcessExit(process, destroyGraceMs)) {
                return true
            }
            process.destroyForcibly()
            return waitForProcessExit(process, destroyGraceMs)
        }

        internal fun waitForProcessExit(process: Process, timeoutMs: Long): Boolean {
            return try {
                process.waitFor(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }
}
