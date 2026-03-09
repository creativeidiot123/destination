package com.ankit.destination.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

object InstalledAppIconLoader {
    private const val TAG = "InstalledAppIconLoader"
    private const val ICON_CACHE_SIZE = 150
    private const val FAILURE_TTL_MS = 60_000L
    private const val FAILURE_CACHE_SIZE = 150

    private val repository = DeduplicatingIconRepository<ImageBitmap>(
        store = LruIconStore<ImageBitmap>(ICON_CACHE_SIZE),
        failureTtlMs = FAILURE_TTL_MS,
        maxFailureEntries = FAILURE_CACHE_SIZE,
        unexpectedFailureLogger = { packageName, throwable ->
            Log.w(TAG, "Failed to load app icon for $packageName", throwable)
        }
    )

    suspend fun loadIcon(context: Context, packageName: String): ImageBitmap? {
        val appContext = context.applicationContext
        return repository.load(packageName) {
            withContext<IconLoadResult<ImageBitmap>>(Dispatchers.IO) {
                try {
                    val packageManager = appContext.packageManager
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val drawable = packageManager.getApplicationIcon(appInfo)
                    val bitmap = drawable.toBitmap(
                        width = drawable.intrinsicWidth.coerceAtMost(144).coerceAtLeast(1),
                        height = drawable.intrinsicHeight.coerceAtMost(144).coerceAtLeast(1),
                        config = Bitmap.Config.ARGB_8888
                    )
                    IconLoadResult.Success(bitmap.asImageBitmap())
                } catch (_: PackageManager.NameNotFoundException) {
                    IconLoadResult.Missing
                } catch (cancellationException: CancellationException) {
                    throw cancellationException
                } catch (throwable: Throwable) {
                    IconLoadResult.Failure(throwable)
                }
            }
        }
    }

    fun clearCache() {
        repository.clear()
    }
}

internal sealed interface IconLoadResult<out T> {
    data class Success<T>(val value: T) : IconLoadResult<T>
    data object Missing : IconLoadResult<Nothing>
    data class Failure(val throwable: Throwable) : IconLoadResult<Nothing>
}

internal interface IconStore<T> {
    fun get(key: String): T?
    fun put(key: String, value: T)
    fun remove(key: String)
    fun clear()
}

internal class LruIconStore<T>(
    maxSize: Int
) : IconStore<T> {
    private val cache = LruCache<String, T>(maxSize)

    override fun get(key: String): T? = synchronized(cache) {
        cache.get(key)
    }

    override fun put(key: String, value: T) {
        synchronized(cache) {
            cache.put(key, value)
        }
    }

    override fun remove(key: String) {
        synchronized(cache) {
            cache.remove(key)
        }
    }

    override fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }
}

internal class DeduplicatingIconRepository<T>(
    private val store: IconStore<T>,
    private val failureTtlMs: Long,
    private val maxFailureEntries: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val unexpectedFailureLogger: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val loadLocks = ConcurrentHashMap<String, Mutex>()
    private val failureLock = Any()
    private val recentFailures = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > maxFailureEntries
        }
    }

    suspend fun load(
        key: String,
        loader: suspend () -> IconLoadResult<T>
    ): T? {
        store.get(key)?.let { return it }
        if (hasFreshFailure(key)) return null

        val lock = loadLocks.getOrPut(key) { Mutex() }
        try {
            return lock.withLock {
                store.get(key)?.let { return@withLock it }
                if (hasFreshFailure(key)) return@withLock null

                when (val result = loader()) {
                    is IconLoadResult.Success -> {
                        clearFailure(key)
                        store.put(key, result.value)
                        result.value
                    }

                    IconLoadResult.Missing -> {
                        recordFailure(key, null)
                        null
                    }

                    is IconLoadResult.Failure -> {
                        recordFailure(key, result.throwable)
                        null
                    }
                }
            }
        } finally {
            if (!lock.isLocked) {
                loadLocks.remove(key, lock)
            }
        }
    }

    fun clear(key: String? = null) {
        if (key == null) {
            store.clear()
            synchronized(failureLock) {
                recentFailures.clear()
            }
            return
        }

        store.remove(key)
        synchronized(failureLock) {
            recentFailures.remove(key)
        }
    }

    companion object {
        internal fun isFailureFresh(
            lastFailureAtMs: Long,
            nowMs: Long,
            ttlMs: Long
        ): Boolean {
            if (lastFailureAtMs <= 0L || ttlMs <= 0L) return false
            val ageMs = nowMs - lastFailureAtMs
            if (ageMs < 0L) return false
            return ageMs < ttlMs
        }
    }

    private fun hasFreshFailure(key: String): Boolean {
        return synchronized(failureLock) {
            val lastFailureAtMs = recentFailures[key] ?: return@synchronized false
            if (isFailureFresh(lastFailureAtMs, clock(), failureTtlMs)) {
                true
            } else {
                recentFailures.remove(key)
                false
            }
        }
    }

    private fun clearFailure(key: String) {
        synchronized(failureLock) {
            recentFailures.remove(key)
        }
    }

    private fun recordFailure(
        key: String,
        throwable: Throwable?
    ) {
        val nowMs = clock()
        val shouldLog = synchronized(failureLock) {
            val lastFailureAtMs = recentFailures[key]
            val alreadyFresh = lastFailureAtMs != null &&
                isFailureFresh(lastFailureAtMs, nowMs, failureTtlMs)
            recentFailures[key] = nowMs
            throwable != null && !alreadyFresh
        }

        if (shouldLog && throwable != null) {
            unexpectedFailureLogger(key, throwable)
        }
    }
}
