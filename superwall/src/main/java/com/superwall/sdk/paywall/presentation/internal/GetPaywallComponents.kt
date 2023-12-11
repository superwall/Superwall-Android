package com.superwall.sdk.paywall.presentation.internal

import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.get_paywall.PaywallComponents
import com.superwall.sdk.paywall.presentation.internal.operators.checkUserSubscription
import com.superwall.sdk.paywall.presentation.internal.operators.confirmHoldoutAssignment
import com.superwall.sdk.paywall.presentation.internal.operators.confirmPaywallAssignment
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.internal.operators.evaluateRules
import com.superwall.sdk.paywall.presentation.internal.operators.getPaywallViewController
import com.superwall.sdk.paywall.presentation.internal.operators.getPresenterIfNecessary
import com.superwall.sdk.paywall.presentation.internal.operators.waitForSubsStatusAndConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Runs a pipeline of operations to get a paywall to present and associated components.
 *
 * @param request The presentation request.
 * @param publisher A `MutableStateFlow` that gets sent `PaywallState` objects.
 * @return A `PaywallComponents` object that contains objects associated with the
 * paywall view controller.
 * @throws PresentationPipelineError object associated with stages of the pipeline.
 */
@Throws(Throwable::class)
suspend fun Superwall.getPaywallComponents(
    request: PresentationRequest,
    publisher: MutableSharedFlow<PaywallState>? = null
): PaywallComponents {
    waitForSubsStatusAndConfig(request, publisher)
    // TODO:
//    val debugInfo = logPresentation(request)
    val debugInfo = emptyMap<String, Any>()

    // TODO:
//        checkDebuggerPresentation(request, publisher)

    val rulesOutcome = evaluateRules(request)

    checkUserSubscription(
        request = request,
        triggerResult = rulesOutcome.triggerResult,
        paywallStatePublisher = publisher
    )

    confirmHoldoutAssignment(request = request, rulesOutcome = rulesOutcome)

    val paywallViewController = getPaywallViewController(request, rulesOutcome, debugInfo, publisher, dependencyContainer)

    val presenter = getPresenterIfNecessary(paywallViewController, rulesOutcome, request, publisher)

    confirmPaywallAssignment(rulesOutcome.confirmableAssignment, request, request.flags.isDebuggerLaunched)

    return PaywallComponents(
        viewController = paywallViewController,
        presenter = presenter,
        rulesOutcome = rulesOutcome,
        debugInfo = debugInfo
    )
}
