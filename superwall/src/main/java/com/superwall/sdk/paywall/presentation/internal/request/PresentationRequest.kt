package com.superwall.sdk.paywall.presentation.internal

import android.app.Activity
import com.superwall.sdk.analytics.model.TriggerSessionTrigger
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import kotlinx.coroutines.flow.StateFlow


sealed class PresentationRequestType {

    object Presentation : PresentationRequestType()
    data class GetPaywall(val adapter: PaywallViewControllerDelegateAdapter) :
        PresentationRequestType()

    object GetPresentationResult : PresentationRequestType()
    object GetImplicitPresentationResult : PresentationRequestType()

    val description: String
        get() = when (this) {
            is Presentation -> "presentation"
            is GetPaywall -> "getPaywallViewController"
            is GetPresentationResult -> "getPresentationResult"
            is GetImplicitPresentationResult -> "getImplicitPresentationResult"
            else -> "Unknown"
        }

    val couldPresent: Boolean
        get() = when (this) {
            is Presentation, is GetPaywall -> true
            is GetPresentationResult, is GetImplicitPresentationResult -> false
            else -> false
        }

    val paywallVcDelegateAdapter: PaywallViewControllerDelegateAdapter?
        get() = if (this is GetPaywall) this.adapter else null

    val hasObjcDelegate: Boolean
        get() = false

    companion object {
        fun areEqual(lhs: PresentationRequestType, rhs: PresentationRequestType): Boolean {
            return when {
                lhs is GetPaywall && rhs is GetPaywall -> lhs.adapter == rhs.adapter
                else -> lhs == rhs
            }
        }
    }
}


internal data class PresentationRequest(
    val presentationInfo: PresentationInfo,
    var presenter: Activity? = null,
    var paywallOverrides: PaywallOverrides? = null,
    var flags: Flags,

    ) {
    data class Flags(
        var isDebuggerLaunched: Boolean,
        var subscriptionStatus: StateFlow<SubscriptionStatus?>,
        var isPaywallPresented: Boolean,
        var type: PresentationRequestType
    )

    /**
     * The source function type that initiated the presentation request.
     */
    val presentationSourceType: String?
        get() = when (presentationInfo.triggerType) {
            TriggerSessionTrigger.TriggerType.IMPLICIT -> "implicit"
            TriggerSessionTrigger.TriggerType.EXPLICIT -> when (flags.type) {
                is PresentationRequestType.GetPaywall -> "getPaywall"
                is PresentationRequestType.Presentation -> "register"
                is PresentationRequestType.GetPresentationResult -> null
                is PresentationRequestType.GetImplicitPresentationResult -> null
                else -> null
            }
        }
}