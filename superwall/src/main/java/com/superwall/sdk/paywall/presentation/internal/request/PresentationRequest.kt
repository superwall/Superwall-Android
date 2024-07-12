package com.superwall.sdk.paywall.presentation.internal

import android.app.Activity
import com.superwall.sdk.analytics.model.TriggerSessionTrigger
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.vc.delegate.PaywallViewDelegateAdapter
import kotlinx.coroutines.flow.StateFlow

sealed class PresentationRequestType {
    object Presentation : PresentationRequestType()

    data class GetPaywall(
        val adapter: PaywallViewDelegateAdapter,
    ) : PresentationRequestType()

    object GetPresentationResult : PresentationRequestType()

    object GetImplicitPresentationResult : PresentationRequestType()

    val description: String
        get() =
            when (this) {
                is Presentation -> "presentation"
                is GetPaywall -> "getPaywallViewController"
                is GetPresentationResult -> "getPresentationResult"
                is GetImplicitPresentationResult -> "getImplicitPresentationResult"
                else -> "Unknown"
            }

    @Deprecated("Will be removed in the upcoming versions, use paywallViewDelegateAdapter instead")
    val paywallVcDelegateAdapter: PaywallViewDelegateAdapter? = paywallViewDelegateAdapter

    val paywallViewDelegateAdapter: PaywallViewDelegateAdapter?
        get() = if (this is GetPaywall) this.adapter else null

    companion object {
        fun areEqual(
            lhs: PresentationRequestType,
            rhs: PresentationRequestType,
        ): Boolean =
            when {
                lhs is GetPaywall && rhs is GetPaywall -> lhs.adapter == rhs.adapter
                else -> lhs == rhs
            }
    }
}

data class PresentationRequest(
    val presentationInfo: PresentationInfo,
    var presenter: Activity? = null,
    var paywallOverrides: PaywallOverrides? = null,
    var flags: Flags,
) {
    data class Flags(
        var isDebuggerLaunched: Boolean,
        var subscriptionStatus: StateFlow<SubscriptionStatus?>,
        var isPaywallPresented: Boolean,
        var type: PresentationRequestType,
    )

    /**
     * The source function type that initiated the presentation request.
     */
    val presentationSourceType: String?
        get() =
            when (presentationInfo.triggerType) {
                TriggerSessionTrigger.TriggerType.IMPLICIT -> "implicit"
                TriggerSessionTrigger.TriggerType.EXPLICIT ->
                    when (flags.type) {
                        is PresentationRequestType.GetPaywall -> "getPaywall"
                        is PresentationRequestType.Presentation -> "register"
                        is PresentationRequestType.GetPresentationResult -> null
                        is PresentationRequestType.GetImplicitPresentationResult -> null
                        else -> null
                    }
            }
}
