package com.ankit.destination.packages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerBatch
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.ui.invalidateInstalledAppOptionsCache


class PackageChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val EXECUTOR_KEY = "package-change"
        private const val ACTION_PACKAGE_ADDED_VALUE = Intent.ACTION_PACKAGE_ADDED
        private val pendingEvents = linkedMapOf<String, PendingPackageEvent>()
        private const val PREF_KEY = "runtime_package_receiver"
        private const val PREF_NAME = "destination_runtime_receivers"
        private const val PREF_RUNTIME_REGISTERED = "package_change_runtime_registered"
        @Volatile private var runtimeRegistered = false
        private var runtimeReceiver: PackageChangeReceiver? = null
        private val runtimeRegistrationLock = Any()

        fun ensureRuntimeRegistration(context: Context) {
            if (runtimeRegistered) return
            synchronized(runtimeRegistrationLock) {
                if (runtimeRegistered) return
                runCatching {
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REMOVED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addDataScheme("package")
                    }
                    val appContext = context.applicationContext
                    val receiver = PackageChangeReceiver()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        appContext.registerReceiver(receiver, filter)
                    }
                    runtimeReceiver = receiver
                    runtimeRegistered = true
                    FocusLog.i(FocusEventId.PACKAGE_INSTALL_DETECT, "Runtime package change receiver registered")
                    markRuntimeRegistrationHandled(appContext)
                }.onFailure { throwable ->
                    FocusLog.e(
                        FocusEventId.PACKAGE_INSTALL_DETECT,
                        "Failed to register runtime package change receiver",
                        throwable
                    )
                }
            }
        }

        fun markRuntimeRegistrationHandled(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_RUNTIME_REGISTERED, false)) {
                prefs.edit().putBoolean(PREF_RUNTIME_REGISTERED, true).apply()
            }
        }

        @Suppress("unused")
        fun clearRuntimeRegistrationState(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(PREF_RUNTIME_REGISTERED).apply()
            runtimeRegistered = false
        }

        fun clearPersistedState(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }

        internal fun installedPackageNamesForStrictSchedule(
            events: List<StrictScheduleInstallEvent>
        ): Set<String> {
            return events.asSequence()
                .filter { event ->
                    when (event.normalizedFinalState) {
                        NormalizedPackageEventState.INSTALLED,
                        NormalizedPackageEventState.REPLACED -> true
                        NormalizedPackageEventState.REMOVED -> false
                        null -> event.sawPackageAdded || event.action == ACTION_PACKAGE_ADDED_VALUE
                    }
                }
                .map { event -> event.packageName.trim() }
                .filter(String::isNotBlank)
                .toCollection(linkedSetOf())
        }

        internal enum class NormalizedPackageEventState {
            INSTALLED,
            REMOVED,
            REPLACED
        }

        internal fun normalizedFinalState(
            sawAdded: Boolean,
            sawRemoved: Boolean,
            sawReplaced: Boolean
        ): NormalizedPackageEventState? {
            return when {
                sawReplaced -> NormalizedPackageEventState.REPLACED
                sawAdded -> NormalizedPackageEventState.INSTALLED
                sawRemoved -> NormalizedPackageEventState.REMOVED
                else -> null
            }
        }

        internal data class StrictScheduleInstallEvent(
            val action: String,
            val packageName: String,
            val sawPackageAdded: Boolean,
            val normalizedFinalState: NormalizedPackageEventState? = null
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        if (
            action != Intent.ACTION_PACKAGE_ADDED &&
            action != Intent.ACTION_PACKAGE_REMOVED &&
            action != Intent.ACTION_PACKAGE_REPLACED
        ) {
            return
        }

        invalidateInstalledAppOptionsCache()

        val pending = goAsync()
        synchronized(pendingEvents) {
            val existing = pendingEvents[packageName]
            if (existing == null) {
                pendingEvents[packageName] = PendingPackageEvent(
                    packageName = packageName,
                    sawAdded = action == Intent.ACTION_PACKAGE_ADDED && !replacing,
                    sawRemoved = action == Intent.ACTION_PACKAGE_REMOVED && !replacing,
                    sawReplaced = action == Intent.ACTION_PACKAGE_REPLACED,
                    lastAction = action,
                    ignoredReplaceInProgressCount = if (
                        (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED) && replacing
                    ) {
                        1
                    } else {
                        0
                    },
                    pendingResults = mutableListOf(pending)
                )
            } else {
                existing.lastAction = action
                existing.sawAdded = existing.sawAdded || (action == Intent.ACTION_PACKAGE_ADDED && !replacing)
                existing.sawRemoved = existing.sawRemoved || (action == Intent.ACTION_PACKAGE_REMOVED && !replacing)
                existing.sawReplaced = existing.sawReplaced || action == Intent.ACTION_PACKAGE_REPLACED
                if ((action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED) && replacing) {
                    existing.ignoredReplaceInProgressCount += 1
                }
                existing.pendingResults += pending
                FocusLog.v(FocusEventId.PACKAGE_INSTALL_DETECT, "batched with existing event for $packageName")
            }
        }
        EnforcementExecutor.executeLatest(EXECUTOR_KEY) {
            val eventsToProcess = synchronized(pendingEvents) {
                val out = pendingEvents.values.toList()
                pendingEvents.clear()
                out
            }
            if (eventsToProcess.isEmpty()) {
                eventsToProcess.forEach { event ->
                    event.pendingResults.forEach { result -> runCatching { result.finish() } }
                }
                return@executeLatest
            }

            try {
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) {
                    eventsToProcess.forEach { event ->
                        event.pendingResults.forEach { result -> runCatching { result.finish() } }
                    }
                    return@executeLatest
                }
                val normalizedEvents = eventsToProcess.map { event ->
                    StrictScheduleInstallEvent(
                        action = event.lastAction,
                        packageName = event.packageName,
                        sawPackageAdded = event.sawAdded,
                        normalizedFinalState = normalizedFinalState(
                            sawAdded = event.sawAdded,
                            sawRemoved = event.sawRemoved,
                            sawReplaced = event.sawReplaced
                        )
                    )
                }
                if (normalizedEvents.none { it.normalizedFinalState != null }) {
                    eventsToProcess.forEach { event ->
                        event.pendingResults.forEach { result -> runCatching { result.finish() } }
                    }
                    return@executeLatest
                }
                val strictInstallPackages = installedPackageNamesForStrictSchedule(normalizedEvents)
                strictInstallPackages.forEach { packageNameToStage ->
                    runCatching {
                        engine.onNewPackageInstalledDuringStrictSchedule(packageNameToStage)
                    }.onFailure { throwable ->
                        FocusLog.e(
                            FocusEventId.STRICT_INSTALL_SUSPEND_FAIL,
                            "Strict install staging failed for $packageNameToStage",
                            throwable
                        )
                    }
                }
                val triggers = buildList {
                    normalizedEvents.forEach { event ->
                        add(
                            ApplyTrigger(
                                category = ApplyTriggerCategory.PACKAGE_CHANGE,
                                source = "PackageChangeReceiver",
                                detail = "${event.normalizedFinalState ?: "IGNORED"}:${event.packageName}",
                                packages = setOf(event.packageName)
                            )
                        )
                    }
                    strictInstallPackages.forEach { stagedPackage ->
                        add(
                            ApplyTrigger(
                                category = ApplyTriggerCategory.STRICT_INSTALL_STAGE,
                                source = "PackageChangeReceiver",
                                detail = stagedPackage,
                                packages = setOf(stagedPackage),
                                stagedStrictInstall = true
                            )
                        )
                    }
                }
                FocusLog.d(
                    FocusEventId.PACKAGE_INSTALL_DETECT,
                    "normalized package batch size=${normalizedEvents.size} strictStaged=${strictInstallPackages.size} states=${normalizedEvents.joinToString { "${it.normalizedFinalState ?: "IGNORED"}:${it.packageName}" }}"
                )
                PolicyApplyOrchestrator.requestApply(
                    context = context,
                    triggerBatch = ApplyTriggerBatch(triggers),
                    onComplete = {
                        eventsToProcess.forEach { event ->
                            event.pendingResults.forEach { result -> runCatching { result.finish() } }
                        }
                    }
                )
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.STRICT_INSTALL_SUSPEND_FAIL, "Package change handling failed", t)
                eventsToProcess.forEach { event ->
                    event.pendingResults.forEach { result -> runCatching { result.finish() } }
                }
            }
        }
    }

    private data class PendingPackageEvent(
        val packageName: String,
        var sawAdded: Boolean,
        var sawRemoved: Boolean,
        var sawReplaced: Boolean,
        var lastAction: String,
        var ignoredReplaceInProgressCount: Int,
        val pendingResults: MutableList<BroadcastReceiver.PendingResult>
    )
}
