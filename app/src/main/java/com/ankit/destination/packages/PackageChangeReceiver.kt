package com.ankit.destination.packages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PackageResolver
import com.ankit.destination.policy.PolicyEngine


class PackageChangeReceiver : BroadcastReceiver() {
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

        FocusLog.i(FocusEventId.PACKAGE_INSTALL_DETECT, "PackageChangeReceiver: $action pkg=$packageName replacing=$replacing")
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
                FocusLog.v(FocusEventId.PACKAGE_INSTALL_DETECT, "  batched with existing event for $packageName")
            }
        }
        EnforcementExecutor.executeLatest(EXECUTOR_KEY) {
            val events = synchronized(pendingEvents) {
                pendingEvents.values.toList().also { pendingEvents.clear() }
            }
            if (events.isEmpty()) {
                FocusLog.v(FocusEventId.PACKAGE_INSTALL_DETECT, "PackageChangeReceiver: no pending events")
                return@executeLatest
            }
            FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "┌── PackageChangeReceiver processing ${events.size} events")
            try {
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) {
                    FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "└── NOT device owner, skipping")
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

                var shouldEnforce = false
                var trigger = "package_change"

                events.forEach { event ->
                    val changedPackage = event.packageName
                    FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│ evaluating: ${event.action} pkg=$changedPackage")
                    if (changedPackage == managedVpnPackage) {
                        FocusLog.i(
                            FocusEventId.BOOT_REAPPLY,
                            "│ 🔴 Managed VPN package changed action=${event.action} pkg=$changedPackage"
                        )
                        shouldEnforce = true
                        trigger = "managed_vpn:${event.action}:$changedPackage"
                        return@forEach
                    }
                    if (alwaysAllowed.contains(changedPackage)) {
                        FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│   → SKIP: always-allowed")
                        return@forEach
                    }

                    val shouldBlockForAlwaysBlocked = alwaysBlocked.contains(changedPackage)
                    val suspendable = resolver.filterSuspendable(setOf(changedPackage), allowlist)
                    FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│   alwaysBlocked=$shouldBlockForAlwaysBlocked suspendable=${suspendable.isNotEmpty()}")
                    if (suspendable.isEmpty() && !shouldBlockForAlwaysBlocked) {
                        FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│   → SKIP: not suspendable and not always-blocked")
                        return@forEach
                    }

                    val strictStaged = engine.onNewPackageInstalledDuringStrictSchedule(changedPackage)
                    FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│   strictStaged=$strictStaged")
                    if (!strictStaged && !shouldBlockForAlwaysBlocked) {
                        FocusLog.i(
                            FocusEventId.STRICT_INSTALL_SUSPEND,
                            "│   → SKIP: no strict action pkg=$changedPackage"
                        )
                        return@forEach
                    }

                    shouldEnforce = true
                    trigger = "${event.action}:$changedPackage"
                    FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│   → WILL ENFORCE trigger=$trigger")
                }

                if (shouldEnforce) {
                    FocusLog.i(FocusEventId.PACKAGE_INSTALL_DETECT, "│ enforcing: trigger=$trigger")
                    engine.requestApplyNow(reason = "PackageChangeReceiver:$trigger")
                } else {
                    FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "│ no enforcement needed")
                }
                FocusLog.d(FocusEventId.PACKAGE_INSTALL_DETECT, "└── PackageChangeReceiver done")
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.STRICT_INSTALL_SUSPEND_FAIL, "Package change handling failed", t)
            } finally {
                events.forEach { event ->
                    event.pendingResults.forEach { result ->
                        runCatching { result.finish() }
                    }
                }
            }
        }
    }

    private data class PendingPackageEvent(
        val packageName: String,
        var action: String,
        val pendingResults: MutableList<BroadcastReceiver.PendingResult>
    )

    private companion object {
        private const val EXECUTOR_KEY = "package-change"
        private val pendingEvents = linkedMapOf<String, PendingPackageEvent>()
    }
}
