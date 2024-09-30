package com.superwall.sdk.paywall.presentation.get_paywall

import android.app.Activity
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.getPaywallComponents
import com.superwall.sdk.paywall.presentation.internal.operators.logErrors
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.paywall.vc.PaywallView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class PaywallComponents(
    val view: PaywallView,
    val presenter: Activity?,
    val rulesOutcome: RuleEvaluationOutcome,
    val debugInfo: Map<String, Any>,
) {
    @Deprecated("Will be removed in the upcoming versions, use PaywallComponents.view instead")
    val viewController: PaywallView = view
}

/**
 * Gets a paywall to present, publishing [PaywallState] objects that provide updates on the lifecycle of the paywall.
 *
 * @param request A presentation request of type [PresentationRequest] to feed into a presentation pipeline.
 * @param publisher A [MutableSharedFlow] that emits [PaywallState] objects.
 * @return A [PaywallView] to present.
 * @throws Throwable if an error occurs during the process.
 */
@Throws(Throwable::class)
suspend fun Superwall.getPaywall(
    request: PresentationRequest,
    publisher: MutableSharedFlow<PaywallState> = MutableSharedFlow(),
): PaywallView =
    try {
        val paywallComponents = getPaywallComponents(request, publisher)

        paywallComponents.view.set(
            request = request,
            paywallStatePublisher = publisher,
            unsavedOccurrence = paywallComponents.rulesOutcome.unsavedOccurrence,
        )
        paywallComponents.view
    } catch (error: Throwable) {
        logErrors(request, error = error)
        // TODO: throw the proper error
//        throw mapError(error, toObjc = toObjc)
        throw error
    }

/**
 * Gets a paywall to present synchronously, providing updates on the lifecycle of the paywall through a callback.
 * Warning: This blocks the calling thread until the paywall is returned.
 *
 * @param request A presentation request of type [PresentationRequest] to feed into a presentation pipeline.
 * @param onStateChanged A callback function that receives [PaywallState] updates.
 * @return A [PaywallView] to present.
 * @throws Throwable if an error occurs during the process.
 */
@Throws(Throwable::class)
fun Superwall.getPaywallSync(
    request: PresentationRequest,
    onStateChanged: (PaywallState) -> Unit = {},
): PaywallView {
    val scope = Superwall.instance.ioScope
    val publisher = MutableSharedFlow<PaywallState>()
    scope.launch {
        publisher.collectLatest {
            onStateChanged(it)
        }
    }
    return runBlocking {
        getPaywall(request, publisher)
    }
}
