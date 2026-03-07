package com.ankit.destination.packages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PackageResolver
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.PolicyStore


class PackageChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val EXECUTOR_KEY = "package-change"
        private val pendingEvents = linkedMapOf<String, PendingPackageEvent>()
        private const val PREF_KEY = "runtime_package_receiver"
        private const val PREF_NAME = "destination_runtime_receivers"
        private const val PREF_RUNTIME_REGISTERED = "package_change_runtime_registered"
        private var runtimeRegistered = false
        private var runtimeReceiver: PackageChangeReceiver? = null

        fun ensureRuntimeRegistration(context: Context) {
            if (runtimeRegistered) return
            synchronized(PendingPackageEvent) {
                if (runtimeRegistered) return
                runCatching {
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addDataScheme("package")
                    }
                    val appContext = context.applicationContext
                    val receiver = PackageChangeReceiver()
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, filter)
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
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        if (action == Intent.ACTION_PACKAGE_ADDED && replacing) {
            FocusLog.v(FocusEventId.PACKAGE_INSTALL_DETECT, "PackageChangeReceiver: IGNORED add+replacing pkg=$packageName")
            return
        }
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) {
            return
        }

        val pending = goAsync()
        synchronized(pendingEvents) {
            val existing = pendingEvents[packageName]
            if (existing == null) {
                pendingEvents[packageName] = PendingPackageEvent(
                    packageName = packageName,
                    action = action,
                    pendingResults = mutableListOf(pending)
                )
            } else {
                existing.action = action
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

                val resolver = PackageResolver(context)
                val managedVpnPackage = engine.getManagedVpnPackage()
                val alwaysAllowed = engine.getAlwaysAllowedApps()
                val alwaysBlocked = engine.getAlwaysBlockedApps()
                val allowlist = resolver.resolveAllowlist(
                    userChosenEmergencyApps = engine.getEmergencyApps(),
                    alwaysAllowedApps = alwaysAllowed
                ).packages
                val store = PolicyStore(context)
                var shouldEnforce = false
                var trigger = "package_change"

                if (store.isScheduleStrictComputed()) {
                    eventsToProcess.forEach { event ->
                        runCatching {
                            engine.onNewPackageInstalledDuringStrictSchedule(event.packageName)
                        }.onFailure { throwable ->
                            FocusLog.e(
                                FocusEventId.PACKAGE_INSTALL_DETECT,
                                "Strict-install staging failed for ${event.packageName}",
                                throwable
                            )
                        }
                    }
                }

                eventsToProcess.forEach { event ->
                    val changedPackage = event.packageName
                    if (changedPackage == managedVpnPackage) {
                        shouldEnforce = true
                        trigger = "managed_vpn:${event.action}:$changedPackage"
                        return@forEach
                    }
                    if (alwaysAllowed.contains(changedPackage)) {
                        return@forEach
                    }

                    val shouldBlockForAlwaysBlocked = alwaysBlocked.contains(changedPackage)
                    val suspendable = resolver.filterSuspendable(setOf(changedPackage), allowlist)
                    if (suspendable.isEmpty() && !shouldBlockForAlwaysBlocked) {
                        return@forEach
                    }

                    if (!shouldBlockForAlwaysBlocked) {
                        val strictStaged = engine.onNewPackageInstalledDuringStrictSchedule(changedPackage)
                        if (!strictStaged) return@forEach
                    }

                    shouldEnforce = true
                    trigger = "${event.action}:$changedPackage"
                }

                if (shouldEnforce) {
                    val triggerLabel = eventsToProcess.joinToString(",") { "${it.action}:${it.packageName}" }
                    PolicyApplyOrchestrator.requestApply(
                        context = context,
                        reason = "PackageChangeReceiver:$triggerLabel",
                        onComplete = {
                            eventsToProcess.forEach { event ->
                                event.pendingResults.forEach { result -> runCatching { result.finish() } }
                            }
                        }
                    )
                } else {
                    eventsToProcess.forEach { event ->
                        event.pendingResults.forEach { result -> runCatching { result.finish() } }
                    }
                }
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
        var action: String,
        val pendingResults: MutableList<BroadcastReceiver.PendingResult>
    )
}

