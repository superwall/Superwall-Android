package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.LastPresentationItems
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.flow.MutableStateFlow


/// Stores the presentation request for future use.
internal suspend fun Superwall.storePresentationObjects(
    request: PresentationRequest?,
    paywallStatePublisher: MutableStateFlow<PaywallState>
) {
    val request = request ?: return

    val lastPaywallPresentation = LastPresentationItems(
        request,
        paywallStatePublisher
    )
    presentationItems.setLast(lastPaywallPresentation)
}
