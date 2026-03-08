package com.ankit.destination.ui

private const val DEFAULT_REFRESH_STALE_MS = 2_000L

internal class RefreshCoordinator(
    private val staleAfterMs: Long = DEFAULT_REFRESH_STALE_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var isLoading = false
    private var rerunAfterLoad = false
    private var lastSuccessfulRefreshAtMs = 0L

    fun tryStart(force: Boolean = false): Boolean {
        synchronized(lock) {
            if (isLoading) {
                if (force) {
                    rerunAfterLoad = true
                }
                return false
            }
            if (!force && isRefreshFresh(lastSuccessfulRefreshAtMs, clock(), staleAfterMs)) {
                return false
            }
            isLoading = true
            return true
        }
    }

    fun finish(success: Boolean): Boolean {
        synchronized(lock) {
            isLoading = false
            if (success) {
                lastSuccessfulRefreshAtMs = clock()
            }
            val shouldRerun = rerunAfterLoad
            rerunAfterLoad = false
            if (shouldRerun) {
                isLoading = true
            }
            return shouldRerun
        }
    }

    companion object {
        internal fun isRefreshFresh(
            lastSuccessfulRefreshAtMs: Long,
            nowMs: Long,
            staleAfterMs: Long
        ): Boolean {
            if (lastSuccessfulRefreshAtMs <= 0L || staleAfterMs <= 0L) return false
            val ageMs = nowMs - lastSuccessfulRefreshAtMs
            if (ageMs < 0L) return false
            return ageMs < staleAfterMs
        }
    }
}
