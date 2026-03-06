package com.ankit.destination.packages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.ScheduleBlockGroup
import com.ankit.destination.enforce.EnforcementExecutor
import com.ankit.destination.policy.FocusEventId
import com.ankit.destination.policy.FocusLog
import com.ankit.destination.policy.PackageResolver
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleEnforcer
import com.ankit.destination.schedule.ScheduleEvaluator
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        if (action == Intent.ACTION_PACKAGE_ADDED && replacing) {
            return
        }
        if (action != Intent.ACTION_PACKAGE_ADDED) {
            return
        }

        val pending = goAsync()
        EnforcementExecutor.execute {
            try {
                val engine = PolicyEngine(context)
                if (!engine.isDeviceOwner()) return@execute

                val alwaysAllowed = engine.getAlwaysAllowedApps()
                if (alwaysAllowed.contains(packageName)) {
                    return@execute
                }
                val alwaysBlocked = engine.getAlwaysBlockedApps()
                val shouldBlockForAlwaysBlocked = alwaysBlocked.contains(packageName)

                val resolver = PackageResolver(context)
                val allowlist = resolver.resolveAllowlist(
                    userChosenEmergencyApps = engine.getEmergencyApps(),
                    alwaysAllowedApps = alwaysAllowed
                ).packages
                val suspendable = resolver.filterSuspendable(setOf(packageName), allowlist)
                if (suspendable.isEmpty() && !shouldBlockForAlwaysBlocked) return@execute

                var strictActive = engine.isStrictScheduleActive()
                if (!strictActive) {
                    strictActive = runBlocking {
                        val db = FocusDatabase.get(context)
                        val blocks = db.scheduleDao().getEnabledBlocks()
                        val blockGroups = db.scheduleDao().getAllBlockGroups()
                            .groupBy(ScheduleBlockGroup::blockId, ScheduleBlockGroup::groupId)
                            .mapValues { it.value.toSet() }
                        ScheduleEvaluator.evaluate(ZonedDateTime.now(), blocks, blockGroups).strictActive
                    }
                }

                if (!strictActive && !shouldBlockForAlwaysBlocked) {
                    FocusLog.i(
                        FocusEventId.STRICT_INSTALL_SUSPEND,
                        "Install observed pkg=$packageName no strict action"
                    )
                    return@execute
                }

                if (strictActive) {
                    engine.onNewPackageInstalledDuringStrictSchedule(packageName)
                }
                ScheduleEnforcer(context).enforceNow(
                    trigger = "PACKAGE_ADDED:$packageName",
                    includeBudgets = engine.shouldRunBudgetEvaluation()
                )
            } catch (t: Throwable) {
                FocusLog.e(FocusEventId.STRICT_INSTALL_SUSPEND_FAIL, "Package change handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}

