package com.ankit.destination.enforce

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.ankit.destination.policy.EngineResult
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.PolicyVerificationResult
import com.ankit.destination.policy.PolicyState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_UNKNOWN_BLOCK_REASON = "unknown"

object PolicyApplyOrchestrator {
    private const val EXECUTOR_KEY = "policy-apply"
    private const val MAX_REASONS_IN_LABEL = 5
    private const val DEFAULT_TIMEOUT_REASON = "policy_apply_orchestrator_default_timeout"

    private val lock = Any()
    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = mutableListOf<PendingRequest>()

    private data class PendingRequest(
        val id: Long,
        val reason: String,
        val hostActivity: WeakReference<Activity>?,
        val callbacks: MutableList<(ApplyOutcome) -> Unit>
    )

    private data class ApplyOutcome(
        val result: EngineResult?,
        val error: Throwable?
    )

    private fun timeoutFailureResult(message: String = DEFAULT_TIMEOUT_REASON): EngineResult {
        val dummyState = PolicyState(
            mode = com.ankit.destination.policy.ModeState.NORMAL,
            lockTaskAllowlist = emptySet(),
            lockTaskFeatures = 0,
            statusBarDisabled = false,
            suspendTargets = emptySet(),
            previouslySuspended = emptySet(),
            uninstallProtectedPackages = emptySet(),
            previouslyUninstallProtectedPackages = emptySet(),
            restrictions = emptySet(),
            enforceRestrictions = false,
            blockSelfUninstall = false,
            requireAutoTime = false,
            emergencyApps = emptySet(),
            allowlistReasons = emptyMap(),
            vpnRequired = false,
            lockReason = null,
            budgetBlockedPackages = emptySet(),
            touchGrassBreakActive = false,
            primaryReasonByPackage = emptyMap(),
            globalControls = com.ankit.destination.data.GlobalControls()
        )
        return EngineResult(
            success = false,
            message = message,
            verification = PolicyVerificationResult(
                passed = false,
                issues = listOf(message),
                suspendedChecked = 0,
                suspendedMismatchCount = 0,
                lockTaskModeActive = null
            ),
            state = dummyState
        )
    }

    fun requestApply(
        context: Context,
        reason: String,
        hostActivity: Activity? = null,
        onComplete: ((EngineResult) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val cleanReason = reason.trim().ifBlank { DEFAULT_UNKNOWN_BLOCK_REASON }
        val requestId = nextRequestId.getAndIncrement()
        val request = PendingRequest(
            id = requestId,
            reason = cleanReason,
            hostActivity = hostActivity?.let { WeakReference(it) },
            callbacks = mutableListOf<(ApplyOutcome) -> Unit>().also { if (onComplete != null) it.add { outcome ->
                onComplete(
                    outcome.result ?: timeoutFailureResult(
                        outcome.error?.message ?: "Policy apply failed"
                    )
                )
            } }
        )
        synchronized(lock) {
            pendingRequests += request
        }
        EnforcementExecutor.executeLatest(EXECUTOR_KEY) {
            runApplyLoop(appContext)
        }
    }

    suspend fun applyNow(
        context: Context,
        reason: String,
        hostActivity: Activity? = null
    ): EngineResult = suspendCancellableCoroutine { continuation ->
        val appContext = context.applicationContext
        val cleanReason = reason.trim().ifBlank { DEFAULT_UNKNOWN_BLOCK_REASON }
        val requestId = nextRequestId.getAndIncrement()
        val request = PendingRequest(
            id = requestId,
            reason = cleanReason,
            hostActivity = hostActivity?.let { WeakReference(it) },
            callbacks = mutableListOf<(ApplyOutcome) -> Unit>().apply {
                add { outcome ->
                when {
                    !continuation.isActive -> Unit
                    outcome.error != null -> continuation.resumeWith(Result.failure(outcome.error))
                    outcome.result != null -> continuation.resumeWith(Result.success(outcome.result))
                    else -> continuation.resumeWith(
                        Result.failure(IllegalStateException("Policy apply completed without result"))
                    )
                }
            }
            }
        )
        continuation.invokeOnCancellation {
            synchronized(lock) {
                pendingRequests.removeAll { it.id == requestId }
            }
        }
        synchronized(lock) {
            pendingRequests += request
        }
        EnforcementExecutor.executeLatest(EXECUTOR_KEY) {
            runApplyLoop(appContext)
        }
    }

    fun applyNowBlocking(
        context: Context,
        reason: String,
        hostActivity: Activity? = null
    ): EngineResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FocusLog.w(FocusEventId.ENFORCE_EXEC, "applyNowBlocking called on main thread; this can block UI")
        }
        return runBlocking {
            runCatching {
                applyNow(
                    context = context,
                    reason = reason,
                    hostActivity = hostActivity
                )
            }.getOrDefault(timeoutFailureResult())
        }
    }

    private fun runApplyLoop(context: Context) {
        while (true) {
            val batch = synchronized(lock) {
                if (pendingRequests.isEmpty()) return
                pendingRequests.toList().also { pendingRequests.clear() }
            }
            if (batch.isEmpty()) return

            val coalescedReason = buildCoalescedReason(batch)
            val hostActivity = batch
                .asReversed()
                .firstNotNullOfOrNull { it.hostActivity?.get() }

            FocusLog.d(
                FocusEventId.ENFORCE_EXEC,
                "runApplyLoop batchSize=${batch.size} reasons=$coalescedReason"
            )

            val outcome = runCatching {
                val engine = PolicyEngine(context)
                val result = engine.requestApplyNow(
                    hostActivity = hostActivity,
                    reason = coalescedReason
                )
                ApplyOutcome(result = result, error = null)
            }.getOrElse { throwable ->
                FocusLog.e(FocusEventId.ENFORCE_EXEC, "Policy apply failed", throwable)
                ApplyOutcome(
                    result = null,
                    error = throwable
                )
            }

            batch.forEach { request ->
                request.callbacks.forEach { callback ->
                    runCatching { callback(outcome) }
                }
            }

            FocusLog.d(
                FocusEventId.ENFORCE_EXEC,
                "runApplyLoop batch complete batchSize=${batch.size} reason=$coalescedReason"
            )

            if (synchronized(lock) { pendingRequests.isEmpty() }) {
                return
            }
        }
    }

    private fun buildCoalescedReason(requests: List<PendingRequest>): String {
        val distinctReasons = requests
            .asSequence()
            .map { it.reason }
            .distinct()
            .take(MAX_REASONS_IN_LABEL)
            .toList()
        val shownReasons = distinctReasons.joinToString(",")
        return if (requests.size <= MAX_REASONS_IN_LABEL) {
            shownReasons
        } else {
            "coalesced(${requests.size}):$shownReasons"
        }
    }
}
