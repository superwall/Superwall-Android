package com.example.superapp.utils

import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.superapp.test.EventTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Assertions on the events a test recorded. They say what the SDK did, where a screenshot only
 * says what the screen looked like, so a failure reads as "the paywall never opened" instead of
 * "12% of pixels differ". The messages list the recorded events.
 */

/** Names of the recorded events, in order. */
fun EventTimeline.eventNames(): List<String> = allEvents().map { it.eventName }

/**
 * Waits for an event matching [predicate], counting the ones already recorded, and returns it or
 * null after [timeout]. Unlike collecting the event flow, this can't miss an event that fired
 * before the wait started.
 */
suspend fun EventTimeline.awaitEvent(
    timeout: Duration = 10.seconds,
    predicate: (SuperwallEvent) -> Boolean,
): SuperwallEvent? =
    // On a real dispatcher, as the timeout would be skipped inside `runTest`.
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeout) {
            eventsFlow.first { events -> events.any { predicate(it.event) } }
        }
    }?.first { predicate(it.event) }?.event

inline fun <reified T : SuperwallEvent> EventTimeline.assertContains(noinline matching: (T) -> Boolean = { true }): T =
    allEvents().map { it.event }.filterIsInstance<T>().firstOrNull(matching)
        ?: throw AssertionError("Expected a ${T::class.simpleName} event. Recorded: ${eventNames()}")

inline fun <reified T : SuperwallEvent> EventTimeline.assertNone(noinline matching: (T) -> Boolean = { true }) {
    val found = allEvents().map { it.event }.filterIsInstance<T>().firstOrNull(matching)
    if (found != null) {
        throw AssertionError("Expected no ${T::class.simpleName} event but got $found. Recorded: ${eventNames()}")
    }
}

/** Asserts the named events were recorded in this order. Other events may come in between. */
fun EventTimeline.assertInOrder(vararg eventNames: String) {
    val recorded = eventNames()
    var from = 0
    for (name in eventNames) {
        val at = recorded.subList(from, recorded.size).indexOf(name)
        if (at < 0) {
            throw AssertionError("Expected events in order ${eventNames.toList()}, missing '$name'. Recorded: $recorded")
        }
        from += at + 1
    }
}

/** A paywall loaded and opened, and no presentation request was turned down. */
fun EventTimeline.assertPaywallPresented() {
    assertContains<SuperwallEvent.PaywallWebviewLoadComplete>()
    assertContains<SuperwallEvent.PaywallOpen>()
    assertNone<SuperwallEvent.PaywallWebviewLoadFail>()
    assertNone<SuperwallEvent.PaywallPresentationRequest> { it.status != PaywallPresentationRequestStatus.Presentation }
}

/** No paywall opened. With [requestTurnedDown], also that the SDK reported not presenting one. */
fun EventTimeline.assertPaywallNotPresented(requestTurnedDown: Boolean = true) {
    assertNone<SuperwallEvent.PaywallOpen>()
    if (requestTurnedDown) {
        assertContains<SuperwallEvent.PaywallPresentationRequest> {
            it.status == PaywallPresentationRequestStatus.NoPresentation
        }
    }
}

/**
 * Waits until a paywall's page has loaded. Fails as soon as the SDK reports that it won't present
 * one or that the page failed, with the reason and the events so far, where waiting only for the
 * load event would sit until the test times out.
 */
suspend fun EventTimeline.awaitPaywallLoaded(timeout: Duration = 60.seconds) {
    val event =
        awaitEvent(timeout) {
            it is SuperwallEvent.PaywallWebviewLoadComplete ||
                it is SuperwallEvent.PaywallWebviewLoadFail ||
                (it is SuperwallEvent.PaywallPresentationRequest && it.status != PaywallPresentationRequestStatus.Presentation)
        }
    when (event) {
        is SuperwallEvent.PaywallWebviewLoadComplete -> Unit
        is SuperwallEvent.PaywallPresentationRequest ->
            throw AssertionError(
                "The paywall wasn't presented: ${event.status.status}" +
                    (event.reason?.let { " (${it.description}: ${it.info})" } ?: "") +
                    ". Recorded: ${eventNames()}",
            )
        null -> throw AssertionError("No paywall loaded within $timeout. Recorded: ${eventNames()}")
        else -> throw AssertionError("The paywall's page failed to load. Recorded: ${eventNames()}")
    }
}
