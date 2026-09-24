package com.superwall.sdk.customercenter

import android.content.Context
import android.content.Intent
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** What [CustomerCenterActivity] needs from whoever presented it. */
internal interface CustomerCenterSessionHost {
    /** The presentation to show, if any. */
    val session: CustomerCenterManager.Session?

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
    )

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
                dependencies = CustomerCenterDependencies.live(container, resolved, activityReference::get),
                strings = CustomerCenterStrings.bundled(context),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
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
        try {
            launch(context, intent)
        } catch (e: Throwable) {
            session = null
            Logger.debug(
                LogLevel.error,
                LogScope.customerCenter,
                "Couldn't present the Customer Center.",
                error = e,
            )
        }
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

    override fun sessionEnded(ended: Session) {
        if (session !== ended) return
        session = null
        ended.activity.set(null)
        ended.viewModel.dismiss()
        ended.onDismiss?.invoke()
        ended.dismissCompletions.forEach { it() }
    }
}
