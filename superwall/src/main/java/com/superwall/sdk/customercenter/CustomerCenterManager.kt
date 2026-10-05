package com.superwall.sdk.customercenter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.MainScope
import kotlinx.coroutines.Dispatchers

/** What [CustomerCenterActivity] needs from whoever presented it. */
internal interface CustomerCenterSessionHost {
    /** The presentation to show, if any. */
    val session: CustomerCenterManager.Session?

    /** Called by the activity when it's created for [attached]. */
    fun sessionAttached(attached: CustomerCenterManager.Session) {
        attached.didAttach = true
    }

    /** Called by the activity once it has finished for good (not for a configuration change). */
    fun sessionEnded(ended: CustomerCenterManager.Session)
}

/**
 * Owns the single Customer Center presentation for
 * [com.superwall.sdk.Superwall.presentCustomerCenter].
 *
 * The view model lives here rather than in the activity, so it survives the activity being
 * recreated on a configuration change.
 */
internal class CustomerCenterManager(
    private val container: DependencyContainer,
    private val launch: (Context, Intent) -> Unit = { context, intent -> context.startActivity(intent) },
    private val postDelayed: (delayMillis: Long, action: () -> Unit) -> Unit = { delayMillis, action ->
        Handler(Looper.getMainLooper()).postDelayed(action, delayMillis)
    },
    private val makeDependencies: (CustomerCenterConfiguration, activity: () -> Activity?) -> CustomerCenterDependencies =
        { configuration, activity -> CustomerCenterDependencies.live(container, configuration, activity) },
) : CustomerCenterSessionHost {
    internal class Session(
        val viewModel: CustomerCenterViewModel,
        val activity: ActivityReference,
        /**
         * Strongly retains the delegate for the duration of the presentation. The view model's
         * callbacks capture it too, but saying so here keeps that retention deliberate.
         */
        @Suppress("unused") val delegate: CustomerCenterDelegate?,
        val onDismiss: (() -> Unit)?,
        /**
         * The theme of whatever presented the Customer Center, whose `colorPrimary` it tints
         * itself with — see [CustomerCenterTint]. `0` when there's none.
         */
        val hostThemeResId: Int = 0,
        val dismissCompletions: MutableList<() -> Unit> = mutableListOf(),
    ) {
        /** Whether the activity has been created for this presentation, at least once. */
        var didAttach: Boolean = false
    }

    /** The presented Customer Center, if any. Only touched on the main thread. */
    override var session: Session? = null
        private set

    val isPresented: Boolean get() = session != null

    fun present(
        configuration: CustomerCenterConfiguration?,
        delegate: CustomerCenterDelegate?,
        onDismiss: (() -> Unit)?,
    ) {
        if (isPresented) {
            Logger.debug(LogLevel.warn, LogScope.customerCenter, "Customer Center is already presented.")
            return
        }
        val resolved = configuration ?: container.makeSuperwallOptions().customerCenter
        val activityReference = ActivityReference()
        val host = container.activityProvider?.getCurrentActivity()
        val context = host ?: container.context
        val viewModel =
            CustomerCenterViewModel(
                configuration = resolved,
                dependencies = makeDependencies(resolved, activityReference::get),
                strings = CustomerCenterStrings.bundled(context),
                scope = MainScope(Dispatchers.Main.immediate),
                callbacks = CustomerCenterCallbacks.from(delegate),
            )
        session =
            Session(
                viewModel = viewModel,
                activity = activityReference,
                delegate = delegate,
                onDismiss = onDismiss,
                hostThemeResId = CustomerCenterTint.hostThemeResId(container.context, host),
            )
        val intent = Intent(context, CustomerCenterActivity::class.java)
        if (host == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val presented = session ?: return
        try {
            launch(context, intent)
        } catch (e: Throwable) {
            presentationFailed(presented, e)
            return
        }
        // Android can refuse an activity start without throwing, such as one from the background.
        // Without this, the session would stay up with no screen and block every later
        // presentation.
        postDelayed(ATTACH_TIMEOUT_MS) {
            if (session === presented && !presented.didAttach) presentationFailed(presented, null)
        }
    }

    /**
     * Ends a presentation whose activity never appeared. The delegate isn't told the Customer
     * Center was dismissed, as it never opened, but [Session.onDismiss] and any dismiss
     * completions still run so the caller isn't left waiting.
     */
    private fun presentationFailed(
        failed: Session,
        error: Throwable?,
    ) {
        if (session !== failed) return
        session = null
        failed.viewModel.close()
        Logger.debug(
            LogLevel.error,
            LogScope.customerCenter,
            "Couldn't present the Customer Center." +
                if (error == null) " Its activity didn't start: was it presented while the app was in the background?" else "",
            error = error,
        )
        failed.onDismiss?.invoke()
        failed.dismissCompletions.forEach { it() }
    }

    /** Dismisses the presented Customer Center, if any, calling [completion] once it has gone. */
    fun dismiss(completion: (() -> Unit)?) {
        val current = session
        if (current == null) {
            completion?.invoke()
            return
        }
        completion?.let(current.dismissCompletions::add)
        val activity = current.activity.get()
        if (activity == null) {
            // Presented but not yet on screen: nothing to finish, so end the session here.
            sessionEnded(current)
        } else {
            activity.finish()
        }
    }

    companion object {
        /** How long the activity has to appear before the presentation counts as failed. */
        internal const val ATTACH_TIMEOUT_MS = 5_000L
    }

    override fun sessionEnded(ended: Session) {
        if (session !== ended) return
        session = null
        ended.activity.set(null)
        ended.viewModel.dismiss()
        ended.onDismiss?.invoke()
        ended.dismissCompletions.forEach { it() }
    }
}
