package com.superwall.sdk.paywall.view

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

internal class PaywallActivityLaunchException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Whether an Activity is still in a state from which it is safe to launch the paywall Activity.
 *
 * The LifecycleOwner check covers ComponentActivity/AppCompatActivity presenters. Plain framework
 * Activities do not expose lifecycle state, so the platform finishing/destroyed checks remain the
 * best validation available for those callers.
 */
internal fun Activity.canPresentPaywall(): Boolean {
    if (isFinishing || isDestroyed) {
        return false
    }

    return this !is LifecycleOwner || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Contains exceptions thrown by the Android activity-launch boundary only. Callers remain
 * responsible for preparing the PaywallView before invoking this function, so preparation errors
 * are never mistaken for platform launch failures.
 */
internal fun launchPaywallActivity(
    context: Context,
    intent: Intent,
): Result<Unit> {
    if (context is Activity && !context.canPresentPaywall()) {
        return Result.failure(
            PaywallActivityLaunchException(
                "The presenter Activity is no longer started or is finishing.",
            ),
        )
    }

    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        context.startActivity(intent)
        Result.success(Unit)
    } catch (error: ActivityNotFoundException) {
        Result.failure(
            PaywallActivityLaunchException(
                "SuperwallPaywallActivity is unavailable in the merged Android manifest.",
                error,
            ),
        )
    } catch (error: RuntimeException) {
        // Android framework/OEM activity-task failures are delivered through this call as runtime
        // exceptions (including RemoteException-backed NullPointerExceptions from system_server).
        Result.failure(
            PaywallActivityLaunchException(
                "Android failed to launch SuperwallPaywallActivity.",
                error,
            ),
        )
    }
}
