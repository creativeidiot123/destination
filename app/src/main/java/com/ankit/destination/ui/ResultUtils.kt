package com.ankit.destination.ui

import kotlinx.coroutines.CancellationException

internal inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Result.failure(throwable)
    }
}
