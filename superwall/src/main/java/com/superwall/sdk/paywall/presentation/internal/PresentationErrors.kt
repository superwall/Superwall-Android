package com.superwall.sdk.paywall.presentation.internal

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

suspend fun Superwall.userIsSubscribed(
    paywallStatePublisher: MutableSharedFlow<PaywallState>?
): PresentationPipelineError {
    val state = PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed())
    paywallStatePublisher?.emit(state)
    return PaywallPresentationRequestStatusReason.UserIsSubscribed()
}