package com.superwall.sdk.paywall.presentation

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingLogic
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.UserInitiatedEvent
import com.superwall.sdk.models.config.FeatureGatingBehavior
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.internallyPresent
import com.superwall.sdk.paywall.presentation.internal.request.PaywallOverrides
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult.Declined
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult.Purchased
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult.Restored
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun Superwall.dismiss() =
    withContext(Dispatchers.Main) {
        val completionSignal = CompletableDeferred<Unit>()

        try {
            paywallView?.let {
                dismiss(paywallView = it, result = PaywallResult.Declined()) {
                    completionSignal.complete(Unit)
                }
            } ?: completionSignal.complete(Unit)
        } catch (e: Exception) {
            Superwall.instance.track(InternalSuperwallEvent.ErrorThrown(e))
            throw e
        }

        completionSignal.await()
    }

suspend fun Superwall.dismissForNextPaywall() =
    withContext(Dispatchers.Main) {
        val completionSignal = CompletableDeferred<Unit>()

        try {
            paywallView?.let {
                dismiss(
                    paywallView = it,
                    result = PaywallResult.Declined(),
                    closeReason = PaywallCloseReason.ForNextPaywall,
                ) {
                    completionSignal.complete(Unit)
                }
            } ?: completionSignal.complete(Unit)
        } catch (e: Exception) {
            Superwall.instance.track(InternalSuperwallEvent.ErrorThrown(e))
            throw e
        }
        completionSignal.await()
    }

fun Superwall.register(
    event: String,
    params: Map<String, Any>? = null,
    handler: PaywallPresentationHandler? = null,
    feature: (() -> Unit)? = null,
) {
    internallyRegister(event, params, handler, feature)
}

private fun Superwall.internallyRegister(
    event: String,
    params: Map<String, Any>? = null,
    handler: PaywallPresentationHandler? = null,
    completion: (() -> Unit)? = null,
) {
    val publisher = MutableSharedFlow<PaywallState>()
    val collectionWillStart = CompletableDeferred<Unit>()

    CoroutineScope(Dispatchers.Main).launch {
        collectionWillStart.complete(Unit)

        publisher.collect { state ->
            try {
                when (state) {
                    is PaywallState.Presented -> {
                        handler?.onPresentHandler?.invoke(state.paywallInfo)
                    }

                    is PaywallState.Dismissed -> {
                        val (paywallInfo, paywallResult) = state
                        handler?.onDismissHandler?.invoke(paywallInfo)
                        when (paywallResult) {
                            is Purchased, is Restored -> {
                                completion?.invoke()
                            }

                            is Declined -> {
                                val closeReason = paywallInfo.closeReason
                                val featureGating = paywallInfo.featureGatingBehavior
                                if (closeReason != PaywallCloseReason.ForNextPaywall && featureGating == FeatureGatingBehavior.NonGated) {
                                    completion?.invoke()
                                }
                                if (closeReason == PaywallCloseReason.WebViewFailedToLoad && featureGating == FeatureGatingBehavior.Gated) {
                                    val error =
                                        InternalPresentationLogic.presentationError(
                                            domain = "SWKPresentationError",
                                            code = 106,
                                            title = "Webview Failed",
                                            value = "Trying to present gated paywall but the webview could not load.",
                                        )
                                    handler?.onErrorHandler?.invoke(error)
                                }
                            }
                        }
                    }

                    is PaywallState.Skipped -> {
                        val (reason) = state
                        handler?.onSkipHandler?.invoke(reason)
                        completion?.invoke()
                    }

                    is PaywallState.PresentationError -> {
                        handler?.onErrorHandler?.invoke(state.error)
                    }
                }
            } catch (e: Exception) {
                Superwall.instance.track(InternalSuperwallEvent.ErrorThrown(e))
                throw e
            }
        }
    }

    serialTaskManager.addTask {
        collectionWillStart.await()
        trackAndPresentPaywall(
            event = event,
            params = params,
            paywallOverrides = null,
            isFeatureGatable = completion != null,
            publisher = publisher,
        )
    }
}

private suspend fun Superwall.trackAndPresentPaywall(
    event: String,
    params: Map<String, Any>? = null,
    paywallOverrides: PaywallOverrides? = null,
    isFeatureGatable: Boolean,
    publisher: MutableSharedFlow<PaywallState>,
) {
    try {
        TrackingLogic.checkNotSuperwallEvent(event)
    } catch (e: Throwable) {
        return
    }

    val trackableEvent =
        UserInitiatedEvent.Track(
            rawName = event,
            canImplicitlyTriggerPaywall = false,
            customParameters = params ?: emptyMap(),
            isFeatureGatable = isFeatureGatable,
        )

    try {
        val trackResult = track(trackableEvent)

        val presentationRequest =
            dependencyContainer.makePresentationRequest(
                PresentationInfo.ExplicitTrigger(trackResult.data),
                paywallOverrides,
                isPaywallPresented = isPaywallPresented,
                type = PresentationRequestType.Presentation,
            )

        internallyPresent(presentationRequest, publisher)
    } catch (e: Exception) {
        Superwall.instance.track(InternalSuperwallEvent.ErrorThrown(e))
        throw e
    }
}
