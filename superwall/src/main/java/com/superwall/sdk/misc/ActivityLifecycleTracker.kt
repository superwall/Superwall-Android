package com.superwall.sdk.misc

import android.app.Activity
import android.app.Application
import android.os.Bundle

class ActivityLifecycleTracker : Application.ActivityLifecycleCallbacks, ActivityProvider {
    private var currentActivity: Activity? = null

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        println("!! onActivityCreated: $activity")
    }

    override fun onActivityStarted(activity: Activity) {
        println("!! onActivityStarted: $activity")
        currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        println("!! onActivityResumed: $activity")
        currentActivity = activity
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        println("!! onActivityDestroyed: $activity")
        if (currentActivity == activity) {
            currentActivity = null
        }
    }

    override fun getCurrentActivity(): Activity? {
        println("!! getCurrentActivity: $currentActivity")
        return currentActivity
    }
}
