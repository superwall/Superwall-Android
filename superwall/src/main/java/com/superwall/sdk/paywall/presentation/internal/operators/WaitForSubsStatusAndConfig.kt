package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Result
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.internal.userIsSubscribed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Kotlin version of `waitForSubsStatusAndConfig` function
internal suspend fun Superwall.waitForSubsStatusAndConfig(
    request: PresentationRequest,
    paywallStatePublisher: MutableSharedFlow<PaywallState>? = null,
    dependencyContainer: DependencyContainer? = null
) {
    val dependencyContainer = dependencyContainer ?: this.dependencyContainer

    val subscriptionStatusTask = getValueWithTimeout(
        task = {
            try {
                request.flags.subscriptionStatus
                    .filter { it != SubscriptionStatus.UNKNOWN }
                    .first()
            } catch (e: CancellationException) {
                // Handle exception, cancel the task, and log timeout and fail the request
                // Logic here...
                CoroutineScope(Dispatchers.Default).launch {
                    val trackedEvent = InternalSuperwallEvent.PresentationRequest(
                        eventData = request.presentationInfo.eventData,
                        type = request.flags.type,
                        status = PaywallPresentationRequestStatus.Timeout,
                        statusReason = PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout(),
                        factory = dependencyContainer
                    )
                    track(trackedEvent)
                }
                Logger.debug(
                    logLevel = LogLevel.info,
                    scope = LogScope.paywallPresentation,
                    message = "Timeout: Superwall.instance.subscriptionStatus has been \"unknown\" for " +
                            "over 5 seconds resulting in a failure."
                )
                val error = InternalPresentationLogic.presentationError(
                    domain = "SWKPresentationError",
                    code = 105,
                    title = "Timeout",
                    value = "The subscription status failed to change from \"unknown\"."
                )
                paywallStatePublisher?.emit(PaywallState.PresentationError(error))
                throw PaywallPresentationRequestStatusReason.SubscriptionStatusTimeout()
            }
        },
        timeout = 5000
    )
    subscriptionStatusTask.cancelTimeout()

    val configState = dependencyContainer.configManager.configState

    if (subscriptionStatusTask.value == SubscriptionStatus.ACTIVE) {
        if (configState.value.getSuccess()?.getConfig() == null) {
            if (configState.value.getSuccess() is ConfigState.Retrieving) {
                // If config is nil and we're still retrieving, wait for <=1 second.
                // At 1s we cancel the task and check config again.
                val result = getValueWithTimeout(
                    task = {
                        try {
                            configState
                                .first { result ->
                                    if (result is Result.Failure) throw result.error
                                    result.getSuccess()?.getConfig() != null
                                }
                        } catch (e: CancellationException) {
                            CoroutineScope(Dispatchers.Default).launch {
                                val trackedEvent = InternalSuperwallEvent.PresentationRequest(
                                    eventData = request.presentationInfo.eventData,
                                    type = request.flags.type,
                                    status = PaywallPresentationRequestStatus.Timeout,
                                    statusReason = PaywallPresentationRequestStatusReason.NoConfig(),
                                    factory = dependencyContainer
                                )
                                track(trackedEvent)
                            }
                            Logger.debug(
                                logLevel = LogLevel.info,
                                scope = LogScope.paywallPresentation,
                                message = "Timeout: The config could not be retrieved in a reasonable time for a subscribed user."
                            )
                            throw userIsSubscribed(paywallStatePublisher)
                        }
                    },
                    timeout = 1000
                )
                result.cancelTimeout()
            } else {
                // If the user is subscribed and there's no config (for whatever reason),
                // just call the feature block.
                throw userIsSubscribed(paywallStatePublisher)
            }
        } else {
            // If the user is subscribed and there is config, continue.
        }
    } else {
        try {
            configState
                .first { result ->
                    if (result is Result.Failure) throw result.error
                    result.getSuccess()?.getConfig() != null
                }
        } catch (e: Throwable) {
            // If config completely dies, then throw an error
            val error = InternalPresentationLogic.presentationError(
                domain = "SWKPresentationError",
                code = 104,
                title = "No Config",
                value = "Trying to present paywall without the Superwall config."
            )
            val state = PaywallState.PresentationError(error)
            paywallStatePublisher?.emit(state)
            throw PaywallPresentationRequestStatusReason.NoConfig()
        }
    }

    // Get the identity. This may or may not wait depending on whether the dev
    // specifically wants to wait for assignments.
    dependencyContainer.identityManager.hasIdentity.first()
}

private data class ValueResult<T>(
    val value: T,
    private val delayJob: Job,
    private val collectionJob: Job
) {
    fun cancelTimeout() {
        delayJob.cancel()
        collectionJob.cancel()
    }
}
/**
 * Executes a given suspending task with a timeout.
 *
 * @param T The type of the result returned by the task.
 * @param task The suspending function to execute.
 * @param timeout Duration in milliseconds after which the task will be cancelled if not completed.
 * @return [ValueResult] object encapsulating the result and the ability to cancel the timeout.
 * @throws CancellationException if the task gets cancelled.
 */
private suspend fun <T> getValueWithTimeout(
    task: suspend () -> T,
    timeout: Long
): ValueResult<T> {
    // Deferred object to hold the result of the 'task'
    val valueResult = CompletableDeferred<T>()

    // Start the given task in a separate coroutine and store its result in 'valueResult'
    val valueTask = CoroutineScope(Dispatchers.Default).async {
        try {
            val result = task()
            valueResult.complete(result)
        } catch (e: CancellationException) {
            // Rethrow any cancellation exception to be handled by the caller
            throw e
        }
    }

    // SharedFlow to act as a signal for when the timeout has occurred
    val publisher = MutableSharedFlow<Unit>()

    // Job to introduce the delay for the timeout
    val delayJob = CoroutineScope(Dispatchers.Default).launch {
        delay(timeout)   // Wait for the timeout duration
        publisher.emit(Unit)  // Emit a signal indicating timeout has occurred
    }

    // Job to listen for the timeout signal and cancel the 'valueTask'
    val collectionJob = CoroutineScope(Dispatchers.Default).launch {
        publisher.collect {
            valueTask.cancel()  // Cancel the task
            valueResult.cancel()  // Cancel the result deferred
        }
    }

    // Await the result of the task and return it wrapped in a ValueResult
    return try {
        ValueResult(valueResult.await(), delayJob, collectionJob)
    } catch (e: CancellationException) {
        // Rethrow any cancellation exception to be handled by the caller
        throw e
    }
}
