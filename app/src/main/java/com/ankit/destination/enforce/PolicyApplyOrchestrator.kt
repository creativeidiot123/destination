package com.ankit.destination.enforce

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerBatch
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.EngineResult
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.PolicyState
import com.ankit.destination.policy.PolicyVerificationResult
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

private const val DEFAULT_TIMEOUT_REASON = "policy_apply_orchestrator_default_timeout"

object PolicyApplyOrchestrator {
    private val lock = Any()
    private val nextRequestId = AtomicLong(1)
    private var pendingBatch: PendingBatch? = null
    private var workerRunning: Boolean = false

    private data class PendingRequest(
        val id: Long,
        val triggerBatch: ApplyTriggerBatch,
        val hostActivity: WeakReference<Activity>?,
        val callbacks: MutableList<(ApplyOutcome) -> Unit>
    )

    private data class PendingBatch(
        val requests: MutableList<PendingRequest> = mutableListOf()
    ) {
        fun append(request: PendingRequest) {
            requests += request
        }

        fun toTriggerBatch(): ApplyTriggerBatch {
            return ApplyTriggerBatch(requests.flatMap { it.triggerBatch.triggers })
        }

        fun preferredHostActivity(): Activity? {
            return requests
                .asReversed()
                .firstNotNullOfOrNull { it.hostActivity?.get() }
        }

        fun size(): Int = requests.size
    }

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
        trigger: ApplyTrigger,
        hostActivity: Activity? = null,
        onComplete: ((EngineResult) -> Unit)? = null
    ) {
        requestApply(
            context = context,
            triggerBatch = ApplyTriggerBatch.single(trigger),
            hostActivity = hostActivity,
            onComplete = onComplete
        )
    }

    fun requestApply(
        context: Context,
        triggerBatch: ApplyTriggerBatch,
        hostActivity: Activity? = null,
        onComplete: ((EngineResult) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val requestId = nextRequestId.getAndIncrement()
        val request = PendingRequest(
            id = requestId,
            triggerBatch = triggerBatch,
            hostActivity = hostActivity?.let(::WeakReference),
            callbacks = mutableListOf<(ApplyOutcome) -> Unit>().also { callbacks ->
                if (onComplete != null) {
                    callbacks += { outcome ->
                        onComplete(
                            outcome.result ?: timeoutFailureResult(
                                outcome.error?.message ?: "Policy apply failed"
                            )
                        )
                    }
                }
            }
        )
        enqueueRequest(appContext, request)
    }

    fun requestApply(
        context: Context,
        reason: String,
        hostActivity: Activity? = null,
        onComplete: ((EngineResult) -> Unit)? = null
    ) {
        requestApply(
            context = context,
            trigger = legacyTrigger(reason),
            hostActivity = hostActivity,
            onComplete = onComplete
        )
    }

    suspend fun applyNow(
        context: Context,
        trigger: ApplyTrigger,
        hostActivity: Activity? = null
    ): EngineResult = applyNow(
        context = context,
        triggerBatch = ApplyTriggerBatch.single(trigger),
        hostActivity = hostActivity
    )

    suspend fun applyNow(
        context: Context,
        triggerBatch: ApplyTriggerBatch,
        hostActivity: Activity? = null
    ): EngineResult = suspendCancellableCoroutine { continuation ->
        val appContext = context.applicationContext
        val requestId = nextRequestId.getAndIncrement()
        val request = PendingRequest(
            id = requestId,
            triggerBatch = triggerBatch,
            hostActivity = hostActivity?.let(::WeakReference),
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
                pendingBatch?.requests?.removeAll { it.id == requestId }
            }
        }
        enqueueRequest(appContext, request)
    }

    suspend fun applyNow(
        context: Context,
        reason: String,
        hostActivity: Activity? = null
    ): EngineResult = applyNow(
        context = context,
        trigger = legacyTrigger(reason),
        hostActivity = hostActivity
    )

    fun applyNowBlocking(
        context: Context,
        triggerBatch: ApplyTriggerBatch,
        hostActivity: Activity? = null
    ): EngineResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FocusLog.w(FocusEventId.ENFORCE_EXEC, "applyNowBlocking called on main thread; this can block UI")
        }
        return runBlocking {
            runCatching {
                applyNow(
                    context = context,
                    triggerBatch = triggerBatch,
                    hostActivity = hostActivity
                )
            }.getOrDefault(timeoutFailureResult())
        }
    }

    fun applyNowBlocking(
        context: Context,
        reason: String,
        hostActivity: Activity? = null
    ): EngineResult {
        return applyNowBlocking(
            context = context,
            triggerBatch = ApplyTriggerBatch.single(legacyTrigger(reason)),
            hostActivity = hostActivity
        )
    }

    private fun enqueueRequest(context: Context, request: PendingRequest) {
        var startWorker = false
        synchronized(lock) {
            val mergedIntoActiveWorker = workerRunning
            val nextBatch = pendingBatch ?: PendingBatch().also { pendingBatch = it }
            nextBatch.append(request)
            if (!workerRunning) {
                workerRunning = true
                startWorker = true
            } else if (mergedIntoActiveWorker) {
                FocusLog.d(
                    FocusEventId.ENFORCE_EXEC,
                    "request merged into pending drain cycle id=${request.id} categories=${request.triggerBatch.categoryCounts}"
                )
            }
        }
        if (startWorker) {
            EnforcementExecutor.execute {
                runApplyLoop(context)
            }
        }
    }

    private fun runApplyLoop(context: Context) {
        while (true) {
            val batch = synchronized(lock) {
                val current = pendingBatch
                pendingBatch = null
                current
            } ?: run {
                synchronized(lock) {
                    workerRunning = false
                }
                return
            }

            val triggerBatch = batch.toTriggerBatch()
            val hostActivity = batch.preferredHostActivity()
            FocusLog.d(
                FocusEventId.ENFORCE_EXEC,
                "runApplyLoop batchSize=${batch.size()} label=${triggerBatch.compatibilityLabel} categories=${triggerBatch.categoryCounts}"
            )

            val outcome = runCatching {
                val engine = PolicyEngine(context)
                val result = runBlocking {
                    engine.requestApplyNowAsync(
                        hostActivity = hostActivity,
                        triggerBatch = triggerBatch
                    )
                }
                ApplyOutcome(result = result, error = null)
            }.getOrElse { throwable ->
                FocusLog.e(FocusEventId.ENFORCE_EXEC, "Policy apply failed", throwable)
                ApplyOutcome(result = null, error = throwable)
            }

            batch.requests.forEach { request ->
                request.callbacks.forEach { callback ->
                    runCatching { callback(outcome) }
                }
            }

            val hasPending = synchronized(lock) { pendingBatch?.requests?.isNotEmpty() == true }
            if (hasPending) {
                FocusLog.d(
                    FocusEventId.ENFORCE_EXEC,
                    "runApplyLoop detected more pending work after batch label=${triggerBatch.compatibilityLabel}; draining again"
                )
            }
        }
    }

    private fun legacyTrigger(reason: String): ApplyTrigger {
        val cleanReason = reason.trim().ifBlank { "unknown" }
        val (source, detail) = cleanReason.split(':', limit = 2).let { parts ->
            when (parts.size) {
                0 -> "unknown" to null
                1 -> parts[0] to null
                else -> parts[0] to parts[1]
            }
        }
        return ApplyTrigger(
            category = when {
                source.contains("boot", ignoreCase = true) -> ApplyTriggerCategory.BOOT
                source.contains("unlock", ignoreCase = true) -> ApplyTriggerCategory.USER_UNLOCKED
                source.contains("schedule", ignoreCase = true) -> ApplyTriggerCategory.SCHEDULE
                source.contains("package", ignoreCase = true) -> ApplyTriggerCategory.PACKAGE_CHANGE
                source.contains("usage", ignoreCase = true) -> ApplyTriggerCategory.USAGE_ACCESS
                source.contains("accessibility", ignoreCase = true) -> ApplyTriggerCategory.ACCESSIBILITY
                source.contains("manual", ignoreCase = true) -> ApplyTriggerCategory.MANUAL
                source.contains("provision", ignoreCase = true) -> ApplyTriggerCategory.PROVISIONING
                source.contains("diagnostic", ignoreCase = true) -> ApplyTriggerCategory.DIAGNOSTICS
                else -> ApplyTriggerCategory.UNKNOWN
            },
            source = source,
            detail = detail
        )
    }
}
