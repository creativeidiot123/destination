package com.ankit.destination.enforce

import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object EnforcementExecutor {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focus-enforce").apply { isDaemon = false }
    }
    private val latestTasks = mutableMapOf<String, LatestTask>()

    fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    fun executeLatest(
        key: String,
        onDropped: () -> Unit = {},
        task: () -> Unit
    ): Boolean {
        var droppedTask: QueuedTask? = null
        synchronized(latestTasks) {
            val queuedTask = QueuedTask(task = task, onDropped = onDropped)
            val pending = latestTasks[key]
            if (pending != null) {
                droppedTask = pending.queuedTask
                pending.queuedTask = queuedTask
                FocusLog.d(FocusEventId.ENFORCE_EXEC, "executeLatest($key) REPLACED queued task")
            } else {
                val latestTask = LatestTask()
                latestTasks[key] = latestTask
                FocusLog.d(FocusEventId.ENFORCE_EXEC, "executeLatest($key) QUEUED new")
                executor.execute {
                    var taskToRun = queuedTask
                    try {
                        while (true) {
                            val startNs = System.nanoTime()
                            FocusLog.d(FocusEventId.ENFORCE_EXEC, "executeLatest($key) EXECUTING")
                            taskToRun.task()
                            val durationMs = (System.nanoTime() - startNs) / 1_000_000.0
                            FocusLog.d(FocusEventId.ENFORCE_EXEC, "executeLatest($key) DONE in %.1fms".format(durationMs))
                            val nextTask = synchronized(latestTasks) {
                                val current = latestTasks[key] ?: return@execute
                                val replacement = current.queuedTask
                                if (replacement == null) {
                                    latestTasks.remove(key)
                                } else {
                                    current.queuedTask = null
                                    FocusLog.d(FocusEventId.ENFORCE_EXEC, "executeLatest($key) has REPLACEMENT task")
                                }
                                replacement
                            }
                            if (nextTask == null) {
                                return@execute
                            }
                            taskToRun = nextTask
                        }
                    } catch (t: Throwable) {
                        FocusLog.e(FocusEventId.ENFORCE_EXEC, "executeLatest($key) FAILED: ${t.message}", t)
                    } finally {
                        val queuedForDrop = synchronized(latestTasks) {
                            latestTasks.remove(key)?.queuedTask
                        }
                        dropQueuedTask(queuedForDrop)
                    }
                }
                return true
            }
        }
        dropQueuedTask(droppedTask)
        return false
    }

    private class LatestTask {
        var queuedTask: QueuedTask? = null
    }

    private data class QueuedTask(
        val task: () -> Unit,
        val onDropped: () -> Unit
    )

    private fun dropQueuedTask(task: QueuedTask?) {
        runCatching { task?.onDropped?.invoke() }
    }
}
