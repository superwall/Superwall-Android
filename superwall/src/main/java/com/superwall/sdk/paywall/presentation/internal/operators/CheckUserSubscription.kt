package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

suspend fun Superwall.checkUserSubscription(
    request: PresentationRequest,
    triggerResult: TriggerResult,
    paywallStatePublisher: MutableStateFlow<PaywallState>
) {
    when (triggerResult) {
        is TriggerResult.Paywall -> return
        else -> {
            val subscriptionStatus = request.flags.subscriptionStatus.first()
            if (subscriptionStatus == SubscriptionStatus.Active) {
                paywallStatePublisher.emit(PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed()))
                paywallStatePublisher.emit(PaywallState.Finalized())
                throw PaywallPresentationRequestStatusReason.UserIsSubscribed()
            }
        }
    }
}

