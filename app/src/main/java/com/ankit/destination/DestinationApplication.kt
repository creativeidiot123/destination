package com.ankit.destination

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.packages.PackageChangeReceiver
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.schedule.AlarmScheduler
import com.ankit.destination.usage.UsageAccessMonitor

class DestinationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AccessibilityStatusMonitor.initialize(this)
        AccessibilityStatusMonitor.refreshNow(this, reason = "process_start")
        UsageAccessMonitor.initialize(this)
        UsageAccessMonitor.refreshNow(
            context = this,
            reason = "process_start",
            requestPolicyRefreshIfChanged = false
        )
        AlarmScheduler(this).scheduleUsageAccessPollIfNeeded()
        PackageChangeReceiver.ensureRuntimeRegistration(this)
        PolicyApplyOrchestrator.requestApply(
            context = this,
            trigger = ApplyTrigger(
                category = ApplyTriggerCategory.PROCESS_START,
                source = "destination_application",
                detail = "process_start"
            )
        )
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                private var startedActivityCount: Int = 0

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount += 1
                    if (startedActivityCount == 1) {
                        AccessibilityStatusMonitor.refreshNow(
                            context = this@DestinationApplication,
                            reason = "app_foreground",
                            requestPolicyRefreshIfChanged = true,
                            minimumIntervalMs = LIFECYCLE_REFRESH_THROTTLE_MS
                        )
                        UsageAccessMonitor.refreshNow(
                            context = this@DestinationApplication,
                            reason = "app_foreground",
                            requestPolicyRefreshIfChanged = true,
                            minimumIntervalMs = LIFECYCLE_REFRESH_THROTTLE_MS
                        )
                        PolicyApplyOrchestrator.requestApply(
                            context = this@DestinationApplication,
                            trigger = ApplyTrigger(
                                category = ApplyTriggerCategory.APP_FOREGROUND,
                                source = "destination_application",
                                detail = "app_foreground"
                            )
                        )
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    AccessibilityStatusMonitor.refreshNow(
                        context = this@DestinationApplication,
                        reason = "activity_resume:${activity.javaClass.simpleName}",
                        requestPolicyRefreshIfChanged = true
                    )
                    UsageAccessMonitor.refreshNow(
                        context = this@DestinationApplication,
                        reason = "activity_resume:${activity.javaClass.simpleName}",
                        requestPolicyRefreshIfChanged = true,
                        minimumIntervalMs = 0L
                    )
                }

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private companion object {
        private const val LIFECYCLE_REFRESH_THROTTLE_MS = 750L
    }
}
