package com.superwall.sdk.paywall.presentation.internal.operators

import android.webkit.WebView
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.internal.userIsSubscribed
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.request.PaywallRequest
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first

@Throws(Throwable::class)
internal suspend fun Superwall.getPaywallView(
    request: PresentationRequest,
    rulesOutcome: RuleEvaluationOutcome,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null,
    dependencyContainer: DependencyContainer,
): PaywallView {
    val experiment =
        getExperiment(
            request = request,
            rulesOutcome = rulesOutcome,
            debugInfo = debugInfo,
            paywallStatePublisher = paywallStatePublisher,
            storage = dependencyContainer.storage,
        )

    val responseIdentifiers =
        ResponseIdentifiers(
            paywallId = experiment.variant.paywallId,
            experiment = experiment,
        )

    var requestRetryCount = 6

    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (subscriptionStatus == SubscriptionStatus.ACTIVE) {
        requestRetryCount = 0
    }

    val paywallRequest =
        dependencyContainer.makePaywallRequest(
            eventData = request.presentationInfo.eventData,
            responseIdentifiers = responseIdentifiers,
            overrides =
                PaywallRequest.Overrides(
                    products = request.paywallOverrides?.productsByName,
                    isFreeTrial = request.presentationInfo.freeTrialOverride,
                ),
            isDebuggerLaunched = request.flags.isDebuggerLaunched,
            presentationSourceType = request.presentationSourceType,
            retryCount = requestRetryCount,
        )
    return try {
        val isForPresentation =
            request.flags.type != PresentationRequestType.GetImplicitPresentationResult &&
                request.flags.type != PresentationRequestType.GetPresentationResult
        val delegate = request.flags.type.paywallViewDelegateAdapter

        val webviewExists = WebView.getCurrentWebViewPackage() != null
        if (webviewExists) {
            dependencyContainer.paywallManager.getPaywallView(
                request = paywallRequest,
                isForPresentation = isForPresentation,
                isPreloading = false,
                delegate = delegate,
            )
        } else {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.paywallPresentation,
                message =
                    "Paywalls cannot be presented because the Android System WebView has been disabled" +
                        " by the user.",
            )
            throw PaywallPresentationRequestStatusReason.NoPaywallView()
        }
    } catch (e: Throwable) {
        if (subscriptionStatus == SubscriptionStatus.ACTIVE) {
            throw userIsSubscribed(paywallStatePublisher)
        } else {
            throw presentationFailure(e, request, debugInfo, paywallStatePublisher)
        }
    }
}

private suspend fun presentationFailure(
    error: Throwable,
    request: PresentationRequest,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableSharedFlow<PaywallState>?,
): Throwable {
    val subscriptionStatus = request.flags.subscriptionStatus.first()
    if (InternalPresentationLogic.userSubscribedAndNotOverridden(
            isUserSubscribed = subscriptionStatus == SubscriptionStatus.ACTIVE,
            overrides =
                InternalPresentationLogic.UserSubscriptionOverrides(
                    isDebuggerLaunched = request.flags.isDebuggerLaunched,
                    shouldIgnoreSubscriptionStatus = request.paywallOverrides?.ignoreSubscriptionStatus,
                    presentationCondition = null,
                ),
        )
    ) {
        paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.UserIsSubscribed()))
        return PaywallPresentationRequestStatusReason.UserIsSubscribed()
    }

    Logger.debug(
        logLevel = LogLevel.error,
        scope = LogScope.paywallPresentation,
        message = "Error Getting Paywall View",
        info = debugInfo,
        error = error,
    )
    paywallStatePublisher?.emit(PaywallState.PresentationError(error))
    return PaywallPresentationRequestStatusReason.NoPaywallView()
}
