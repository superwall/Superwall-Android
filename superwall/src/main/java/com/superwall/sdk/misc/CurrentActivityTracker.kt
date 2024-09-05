package com.superwall.sdk.misc

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger

class CurrentActivityTracker :
    Application.ActivityLifecycleCallbacks,
    ActivityProvider {
    private var currentActivity: Activity? = null

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        Logger.debug(
            LogLevel.debug,
            LogScope.all,
            "!! onActivityCreated: $activity",
        )
    }

    override fun onActivityStarted(activity: Activity) {
        Logger.debug(
            LogLevel.debug,
            LogScope.all,
            "!! onActivityStarted: $activity",
        )
        currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        Logger.debug(
            LogLevel.debug,
            LogScope.all,
            "!! onActivityResumed: $activity",
        )
        currentActivity = activity
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {
        Logger.debug(
            LogLevel.debug,
            LogScope.all,
            "!! onActivityDestroyed: $activity",
        )
        if (currentActivity == activity) {
            currentActivity = null
        }
    }

    override fun getCurrentActivity(): Activity? {
        Logger.debug(
            LogLevel.debug,
            LogScope.all,
            "!! getCurrentActivity: $currentActivity",
        )
        return currentActivity
    }
}
