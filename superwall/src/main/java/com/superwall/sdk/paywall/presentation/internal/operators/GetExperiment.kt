package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.InternalTriggerResult
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationPipelineError
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.rule_logic.RuleEvaluationOutcome
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.flow.MutableSharedFlow

// Assuming you have definitions for all the classes and functions used in the below code.

/**
 * Switches over the trigger result. Continues if a paywall will show.
 * Otherwise, if applicable, it sends a `skipped` state to the paywall state publisher and returns.
 *
 * @param request The `PresentationRequest`.
 * @param rulesOutcome The output from evaluating the rules.
 * @param debugInfo Information to help with debugging.
 * @param paywallStatePublisher A `PassthroughSubject` that gets sent `PaywallState` objects.
 *
 * @return A data class that contains info for the next operation.
 */
@Throws(Throwable::class)
suspend fun Superwall.getExperiment(
    request: PresentationRequest,
    rulesOutcome: RuleEvaluationOutcome,
    debugInfo: Map<String, Any>,
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null,
    storage: Storage,
): Experiment {
    val errorType: PresentationPipelineError

    when (rulesOutcome.triggerResult) {
        is InternalTriggerResult.Paywall -> {
            return rulesOutcome.triggerResult.experiment
        }
        is InternalTriggerResult.Holdout -> {
            activateSession(request, rulesOutcome)
            rulesOutcome.unsavedOccurrence?.let {
                storage.coreDataManager.save(triggerRuleOccurrence = it)
            }
            errorType = PaywallPresentationRequestStatusReason.Holdout(rulesOutcome.triggerResult.experiment)
            paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.Holdout(rulesOutcome.triggerResult.experiment)))
        }
        is InternalTriggerResult.NoRuleMatch -> {
            activateSession(request, rulesOutcome)
            errorType = PaywallPresentationRequestStatusReason.NoRuleMatch()
            paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.NoRuleMatch()))
        }
        is InternalTriggerResult.EventNotFound -> {
            errorType = PaywallPresentationRequestStatusReason.EventNotFound()
            paywallStatePublisher?.emit(PaywallState.Skipped(PaywallSkippedReason.EventNotFound()))
        }
        is InternalTriggerResult.Error -> {
            if (request.flags.type == PresentationRequestType.GetImplicitPresentationResult ||
                request.flags.type == PresentationRequestType.GetPresentationResult
            ) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallPresentation,
                    message = "Error Getting Paywall View Controller",
                    info = debugInfo,
                    error = rulesOutcome.triggerResult.error,
                )
            }
            errorType = PaywallPresentationRequestStatusReason.NoPaywallView()
            paywallStatePublisher?.emit(PaywallState.PresentationError(rulesOutcome.triggerResult.error))
        }
    }

    throw errorType
}

private suspend fun Superwall.activateSession(
    request: PresentationRequest,
    rulesOutcome: RuleEvaluationOutcome,
) {
    if (request.flags.type == PresentationRequestType.GetImplicitPresentationResult ||
        request.flags.type == PresentationRequestType.GetPresentationResult
    ) {
        return
    }
    val sessionEventsManager = dependencyContainer.sessionEventsManager
    sessionEventsManager?.triggerSession?.activateSession(
        presentationInfo = request.presentationInfo,
        triggerResult = rulesOutcome.triggerResult,
    )
}
