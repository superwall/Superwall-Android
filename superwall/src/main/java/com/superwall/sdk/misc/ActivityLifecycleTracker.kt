package com.superwall.sdk.misc

import android.app.Activity
import android.app.Application
import android.os.Bundle

class ActivityLifecycleTracker private constructor() : Application.ActivityLifecycleCallbacks {

    private var currentActivity: Activity? = null

    companion object {
        val instance: ActivityLifecycleTracker by lazy { ActivityLifecycleTracker() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) {
            currentActivity = null
        }
    }

    fun getCurrentActivity(): Activity? {
        return currentActivity
    }
}
