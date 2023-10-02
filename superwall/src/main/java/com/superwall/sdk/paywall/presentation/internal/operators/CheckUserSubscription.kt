package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

suspend fun Superwall.checkUserSubscription(
    request: PresentationRequest,
    triggerResult: InternalTriggerResult,
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null
) {
    when (triggerResult) {
        is InternalTriggerResult.Paywall -> return
        else -> {
            val subscriptionStatus = request.flags.subscriptionStatus.first()
            if (subscriptionStatus == SubscriptionStatus.ACTIVE) {
                paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed()))
                throw PaywallPresentationRequestStatusReason.UserIsSubscribed()
            }
        }
    }
}


