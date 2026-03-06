package com.ankit.destination.enforce

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object EnforcementExecutor {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "focus-enforce").apply { isDaemon = true }
    }

    fun execute(task: () -> Unit) {
        executor.execute(task)
    }
}
