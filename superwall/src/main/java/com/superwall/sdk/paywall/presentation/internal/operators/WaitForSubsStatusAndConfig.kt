package com.superwall.sdk.paywall.presentation.internal.operators

import LogLevel
import LogScope
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.paywall.presentation.internal.InternalPresentationLogic
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationPipelineError
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.presentation.internal.userIsSubscribed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.TimerTask

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
                    message = "Timeout: Superwall.shared.subscriptionStatus has been \"unknown\" for " +
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

    val config = dependencyContainer.configManager.config.value

    if (subscriptionStatusTask.value == SubscriptionStatus.ACTIVE) {
        if (config == null) {
            val result = getValueWithTimeout(
                task = {
                    try {
                        dependencyContainer.configManager.config.first()
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
            // TODO: Implement a status for getting config. If still in a retrieving state do above. If retrying or anything else and no config, do below. Currently it will wait for 1s every time there's no config.
            // If the user is subscribed and there's no config (for whatever reason),
            // just call the feature block.
            //throw userIsSubscribed(paywallStatePublisher)
        } else {
            // If the user is subscribed and there is config, continue.
        }
    } else {
        val result = getValueWithTimeout(
            task = {
                try {
                    dependencyContainer.configManager.config
                        .filterNotNull()
                        .first()
                } catch (e: CancellationException) {
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
            },
            timeout = 15000
        )
        result.cancelTimeout()
        val config = result.value
    }

    // Get the identity. This may or may not wait depending on whether the dev
    // specifically wants to wait for assignments.
    dependencyContainer.identityManager.hasIdentity.first()
}

private data class ValueResult<T>(
    val value: T,
    val delayJob: Job,
    val collectionJob: Job
) {
    fun cancelTimeout() {
        delayJob.cancel()
        collectionJob.cancel()
    }
}
private suspend fun <T> getValueWithTimeout(
    task: suspend () -> T,
    timeout: Long
): ValueResult<T> {
    val valueTask = CoroutineScope(Dispatchers.Default).async {
        try {
            task()
        } catch (e: CancellationException) {
            throw e
        }
    }

    val publisher = MutableSharedFlow<Long>()
    val delayJob = GlobalScope.async(Dispatchers.Default) {
        while (isActive) {
            delay(timeout)
            publisher.emit(System.currentTimeMillis())
            this.cancel()
        }
    }

    val collectionJob = CoroutineScope(Dispatchers.Default).launch {
        publisher.collect {
            valueTask.cancel()
            this.cancel()
        }
    }

    val value = runBlocking {
        valueTask.await()
    }

    return ValueResult(value, delayJob, collectionJob)
}