package com.ankit.destination

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.ankit.destination.schedule.AlarmScheduler
import com.ankit.destination.usage.UsageAccessMonitor

class DestinationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UsageAccessMonitor.initialize(this)
        UsageAccessMonitor.refreshNow(
            context = this,
            reason = "process_start",
            requestPolicyRefreshIfChanged = false
        )
        AlarmScheduler(this).scheduleUsageAccessPollIfNeeded()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                private var startedActivityCount: Int = 0

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount += 1
                    if (startedActivityCount == 1) {
                        UsageAccessMonitor.refreshNow(
                            context = this@DestinationApplication,
                            reason = "app_foreground",
                            requestPolicyRefreshIfChanged = true,
                            minimumIntervalMs = LIFECYCLE_REFRESH_THROTTLE_MS
                        )
                    }
                }

                override fun onActivityResumed(activity: Activity) {
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
