package com.superwall.sdk.paywall.presentation.get_paywall

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.UserInitiatedEvent
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegate
import com.superwall.sdk.paywall.vc.delegate.PaywallViewControllerDelegateAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Throws(Throwable::class)
suspend fun Superwall.getPaywall(
    event: String,
    params: Map<String, Any>? = null,
    paywallOverrides: PaywallOverrides? = null,
    delegate: PaywallViewControllerDelegate,
): PaywallViewController =
    withContext(Dispatchers.Main) {
        val viewController =
            internallyGetPaywall(
                event = event,
                params = params,
                paywallOverrides = paywallOverrides,
                delegate = PaywallViewControllerDelegateAdapter(kotlinDelegate = delegate),
            )

        // Note: Deviation from iOS. Unique to Android. This is also done in `InternalPresentation.kt`.
        // Ideally `InternalPresentation` would call this function to get the paywall, and `InternalPresentation.kt`
        // would only handle presentation. This cannot be done is the shared `GetPaywallComponents.kt` because it's
        // also used for getting a presentation result, and we don't want that to have side effects. Opting to
        // do at the top-most point.
        viewController.prepareToDisplay()

        return@withContext viewController
    }

@Throws(Throwable::class)
private suspend fun Superwall.internallyGetPaywall(
    event: String,
    params: Map<String, Any>? = null,
    paywallOverrides: PaywallOverrides? = null,
    delegate: PaywallViewControllerDelegateAdapter,
): PaywallViewController =
    withContext(Dispatchers.Main) {
        val trackableEvent =
            UserInitiatedEvent.Track(
                rawName = event,
                canImplicitlyTriggerPaywall = false,
                customParameters = params ?: emptyMap(),
                isFeatureGatable = false,
            )
        val trackResult = track(trackableEvent)

        val presentationRequest =
            dependencyContainer.makePresentationRequest(
                PresentationInfo.ExplicitTrigger(trackResult.data),
                paywallOverrides = paywallOverrides,
                isPaywallPresented = false,
                type = PresentationRequestType.GetPaywall(delegate),
            )

        return@withContext getPaywall(presentationRequest)
    }
