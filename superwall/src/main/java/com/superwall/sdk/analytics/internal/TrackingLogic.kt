package com.superwall.sdk.analytics.internal

import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventObjc
import com.superwall.sdk.paywall.vc.PaywallViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.*

sealed class TrackingLogic {
    sealed class ImplicitTriggerOutcome {
        object TriggerPaywall : ImplicitTriggerOutcome()
        object DeepLinkTrigger : ImplicitTriggerOutcome()
        object DisallowedEventAsTrigger : ImplicitTriggerOutcome()
        object DontTriggerPaywall : ImplicitTriggerOutcome()
        object ClosePaywallThenTriggerPaywall : ImplicitTriggerOutcome()
    }

    companion object {
        suspend fun processParameters(
            trackableEvent: Trackable,
            eventCreatedAt: Date,
            appSessionId: String
        ): TrackingParameters = withContext(Dispatchers.Default) {
            val superwallParameters = trackableEvent.getSuperwallParameters().toMutableMap()
            superwallParameters["app_session_id"] = appSessionId

            val customParameters = trackableEvent.customParameters
            val eventName = trackableEvent.rawName

            val delegateParams: MutableMap<String, Any> = mutableMapOf("is_superwall" to true)

            // Add a special property if it's a superwall event
            val isStandardEvent = trackableEvent is TrackableSuperwallEvent

            val eventParams: MutableMap<String, Any> = mutableMapOf(
                "\$is_standard_event" to isStandardEvent,
                "\$event_name" to eventName
            )

            // Filter then assign Superwall parameters
            superwallParameters.forEach { (key, value) ->
                clean(value)?.let {
                    val keyWithDollar = "$$key"
                    eventParams[keyWithDollar] = it

                    // no $ for delegate methods
                    delegateParams[key] = it
                }
            }

            // Filter then assign custom parameters
            customParameters.forEach { (key, value) ->
                clean(value)?.let {
                    if (key.startsWith("$")) {
                        // Log dropping key due to $ signs not allowed
                    } else {
                        delegateParams[key] = it
                        eventParams[key] = it
                    }
                }
            }

            return@withContext TrackingParameters(delegateParams, eventParams)
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun clean(input: Any?): Any? {
            return input

            // TODO: (Analytics) Fix this
//            input?.let { value ->
//                when (value) {
//                    is List<*> -> null
//                    is Map<*, *> -> null
//                    else -> {
//                        try {
//                            Json.encodeToString(JsonElement.serializer(), value)
//                            value
//                        } catch (e: SerializationException) {
//                            when (value) {
//                                is LocalDateTime -> value.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
//                                is URL -> value.toString()
//                                else -> null
//                            }
//                        }
//                    }
//                }
//            } ?: kotlin.run { return null }
//            return null
        }

        @Throws(Exception::class)
        fun checkNotSuperwallEvent(event: String) {
            // Try to create a SuperwallEventObjc from the event string
            val superwallEventObjc = SuperwallEventObjc.values().find { superwallEvent ->
                superwallEvent.rawName == event
            }
            if (superwallEventObjc != null) {
                // Log error saying do not track an event with the same name as a SuperwallEvent
                throw Exception("Do not track an event with the same name as a SuperwallEvent")
            }
        }

        fun canTriggerPaywall(
            event: Trackable,
            triggers: Set<String>,
            paywallViewController: PaywallViewController?
        ): ImplicitTriggerOutcome {
            if (event is TrackableSuperwallEvent && event.superwallEvent.objcEvent == SuperwallEventObjc.DeepLink) {
                return ImplicitTriggerOutcome.DeepLinkTrigger
            }

            println("canTriggerPaywall: ${event.rawName} $triggers")
            if (!triggers.contains(event.rawName)) {
                println("!! canTriggerPaywall: triggers.contains(event.rawName) ${event.rawName} $triggers")
                return ImplicitTriggerOutcome.DontTriggerPaywall
            }

            val notAllowedReferringEventNames: Set<String> = setOf(
                SuperwallEventObjc.TransactionAbandon.rawName,
                SuperwallEventObjc.TransactionFail.rawName,
                SuperwallEventObjc.PaywallDecline.rawName
            )


           val referringEventName = paywallViewController?.paywallInfo?.presentedByEventWithName
            if (referringEventName != null)  {
                if (notAllowedReferringEventNames.contains(referringEventName)) {
                    println("!! canTriggerPaywall: notAllowedReferringEventNames.contains(referringEventName) $referringEventName")
                    return ImplicitTriggerOutcome.DontTriggerPaywall
                }
            }

            if (event is TrackableSuperwallEvent) {
                return when (event.superwallEvent.objcEvent) {
                    SuperwallEventObjc.TransactionAbandon,
                    SuperwallEventObjc.TransactionFail,
                    SuperwallEventObjc.PaywallDecline -> ImplicitTriggerOutcome.ClosePaywallThenTriggerPaywall
                    else -> ImplicitTriggerOutcome.TriggerPaywall
                }
            }

            if (paywallViewController != null) {
                println("!! canTriggerPaywall: paywallViewController != null")
                return ImplicitTriggerOutcome.DontTriggerPaywall
            }

            return ImplicitTriggerOutcome.TriggerPaywall
        }
    }
}