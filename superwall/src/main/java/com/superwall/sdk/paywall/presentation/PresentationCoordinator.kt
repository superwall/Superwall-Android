package com.superwall.sdk.paywall.presentation

import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Owns the state of what is being presented: the current paywall view, the items of the last
 * presentation, and the actions that act on the presented paywall (spinner, alert, notifications).
 *
 * [Superwall] forwards its public presentation members here.
 */
internal class PresentationCoordinator(
    private val currentView: () -> PaywallView?,
    private val ioScope: IOScope,
    private val cancelNotifications: () -> Unit,
) {
    val presentationItems: PresentationItems = PresentationItems()

    /** The presented paywall view, if any. */
    val paywallView: PaywallView?
        get() = currentView()

    val isPaywallPresented: Boolean
        get() = paywallView != null

    /** The info of the presented paywall, or else of the most recently presented one. */
    val latestPaywallInfo: PaywallInfo?
        get() = paywallView?.info ?: presentationItems.paywallInfo

    fun updatePaywallInfo(paywallInfo: PaywallInfo) {
        presentationItems.paywallInfo = paywallInfo
    }

    /** Stores the presentation request for future use. Does nothing when [request] is null. */
    fun storePresentationObjects(
        request: PresentationRequest?,
        paywallStatePublisher: MutableSharedFlow<PaywallState>,
    ) {
        val request = request ?: return
        presentationItems.last = LastPresentationItems(request, paywallStatePublisher)
    }

    fun togglePaywallSpinner(isHidden: Boolean) {
        ioScope.launchWithTracking {
            val paywallView = currentView() ?: return@launchWithTracking
            paywallView.updateState(PaywallViewState.Updates.ToggleSpinner(isHidden))
        }
    }

    fun showAlert(
        title: String?,
        message: String?,
        actionTitle: String?,
        closeActionTitle: String,
        action: (() -> Unit)?,
        onClose: (() -> Unit)?,
    ) {
        paywallView?.showAlert(
            title,
            message,
            actionTitle,
            closeActionTitle,
            action,
            onClose,
        )
    }

    fun cancelAllScheduledNotifications() {
        withErrorTracking {
            cancelNotifications()
        }
    }

    fun reset() {
        presentationItems.reset()
    }
}
