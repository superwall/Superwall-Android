package com.superwall.sdk.analytics.internal.trackable

import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.analytics.superwall.TransactionProduct
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.storage.keys.SubscriptionStatus
import com.superwall.sdk.store.abstractions.product.StoreProduct
import com.superwall.sdk.store.abstractions.transactions.GoogleBillingPurchaseTransaction
import com.superwall.sdk.store.abstractions.transactions.StoreTransactionType
import com.superwall.sdk.store.transactions.TransactionError
import kotlinx.serialization.json.*
import java.net.URL


interface TrackableSuperwallEvent : Trackable {
    val superwallEvent: SuperwallEvent
}

sealed class InternalSuperwallEvent(override val superwallEvent: SuperwallEvent) :
    TrackableSuperwallEvent {

    override val rawName: String
        get() = this.superwallEvent.rawName

    override val canImplicitlyTriggerPaywall: Boolean
        get() = this.superwallEvent.canImplicitlyTriggerPaywall

    class AppOpen(override var customParameters: HashMap<String, Any> = HashMap()) :
        InternalSuperwallEvent(SuperwallEvent.AppOpen()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap()
        }
    }

    class AppInstall(
        val appInstalledAtString: String,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.AppInstall()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "application_installed_at" to appInstalledAtString
            )
        }
    }

    // TODO: Implement the reste

    class AppLaunch(override var customParameters: HashMap<String, Any> = HashMap()) :
        InternalSuperwallEvent(SuperwallEvent.AppLaunch()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> = HashMap()
    }

    class Attributes(
        val appInstalledAtString: String,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.UserAttributes(customParameters)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "application_installed_at" to appInstalledAtString
            )
        }
    }

    class DeepLink(val url: URL, override var customParameters: HashMap<String, Any> = HashMap()) :
        InternalSuperwallEvent(SuperwallEvent.DeepLink(url)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "url" to url.toString(),
                "path" to url.path,
                // TODO: Re-enable these with kotlin equivalents
//                "pathExtension" to url.extension,
//                "lastPathComponent" to url.pathSegments.last(),
                "host" to (url.host ?: ""),
                "query" to (url.query ?: ""),
//                "fragment" to (url.fragment ?: "")
            )
        }
    }

    class FirstSeen(override var customParameters: HashMap<String, Any> = HashMap()) :
        InternalSuperwallEvent(SuperwallEvent.FirstSeen()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> = HashMap()
    }

    class AppClose(override var customParameters: HashMap<String, Any> = HashMap()) :
        InternalSuperwallEvent(SuperwallEvent.AppClose()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> = HashMap()
    }


    class PaywallLoad(
        val state: State,
        val eventData: EventData?,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.PaywallResponseLoadStart(eventData?.name)) {

        sealed class State() {
            class Start() : State()
            class NotFound() : State()
            class Fail() : State()
            class Complete(val paywallInfo: PaywallInfo) : State()
        }


        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            val fromEvent = eventData != null
            val params: HashMap<String, Any> = hashMapOf(
                "is_triggered_from_event" to fromEvent
            )

            when (state) {
                is State.Start -> return params
                is State.NotFound -> return params
                is State.Fail -> return params
                is State.Complete -> return HashMap(
                    state.paywallInfo.eventParams(otherParams = params)
                        .filterValues { it != null } as Map<String, Any>)
            }

        }
    }

    class SubscriptionStatusDidChange(
        val subscriptionStatus: SubscriptionStatus,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.SubscriptionStatusDidChange()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "subscription_status" to subscriptionStatus.description
            )
        }
    }


    class SessionStart(override var customParameters: HashMap<String, Any> = HashMap()) :
        InternalSuperwallEvent(SuperwallEvent.SessionStart()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap()
        }
    }

    class TriggerFire(
        val triggerResult: TriggerResult,
        val triggerName: String,
        override var customParameters: HashMap<String, Any> = HashMap(),
        private val sessionEventsManager: SessionEventsManager
    ) : InternalSuperwallEvent(
        SuperwallEvent.TriggerFire(
            eventName = triggerName,
            result = triggerResult
        )
    ) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            val params: HashMap<String, Any> = hashMapOf(
                "trigger_name" to triggerName
            )

            // TODO: Fix trigger session
//            val triggerSession = sessionEventsManager.triggerSession.activeTriggerSession
//            if (triggerSession != null) {
//                params["trigger_session_id"] = triggerSession.id
//            }

            return when (triggerResult) {
                is TriggerResult.NoRuleMatch -> {
                    params.apply {
                        this["result"] = "no_rule_match"
                    }
                }
                is TriggerResult.Holdout -> {
                    params.apply {
                        this["variant_id"] = triggerResult.experiment.variant.id
                        this["experiment_id"] = triggerResult.experiment.id
                        this["result"] = "holdout"
                    }
                }
                is TriggerResult.Paywall -> {
                    params.apply {
                        this["variant_id"] = triggerResult.experiment.variant.id
                        this["experiment_id"] = triggerResult.experiment.id
                        this["paywall_identifier"] =
                            triggerResult.experiment.variant.paywallId ?: ""
                        this["result"] = "present"
                    }
                }
                is TriggerResult.EventNotFound -> {
                    params.apply {
                        this["result"] = "eventNotFound"
                    }
                }
                is TriggerResult.Error -> {
                    params.apply {
                        this["result"] = "error"
                    }
                }
            }
        }
    }

    class PresentationRequest(
        val eventData: EventData?,
        val type: PresentationRequestType,
        val status: PaywallPresentationRequestStatus,
        val statusReason: PaywallPresentationRequestStatusReason?,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(
        SuperwallEvent.PaywallPresentationRequest(
            status = status,
            reason = statusReason
        )
    ) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "source_event_name" to (eventData?.name ?: ""),
                "pipeline_type" to type.description,
                "status" to status.status,
                "status_reason" to (statusReason?.description ?: "")
            )
        }
    }

    class PaywallOpen(
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.PaywallOpen(paywallInfo = paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams())
        }
    }

    class PaywallClose(
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.PaywallClose(paywallInfo = paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams())
        }
    }

    class PaywallDecline(
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.PaywallDecline(paywallInfo = paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams())
        }
    }

    class Transaction(
        val state: State,
        val paywallInfo: PaywallInfo,
        val product: StoreProduct?,
        val model: StoreTransactionType?,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : TrackableSuperwallEvent {

        sealed class State {
            class Start(val product: StoreProduct) : State()
            class Fail(val error: TransactionError) : State()
            class Abandon(val product: StoreProduct) : State()
            class Complete(val product: StoreProduct, val transaction: StoreTransactionType?) :
                State()

            class Restore : State()
            class Timeout : State()
        }

        override val superwallEvent: SuperwallEvent
            get() = when (state) {
                is State.Start -> SuperwallEvent.TransactionStart(
                    product = state.product,
                    paywallInfo = paywallInfo
                )
                is State.Fail -> SuperwallEvent.TransactionFail(
                    error = state.error,
                    paywallInfo = paywallInfo
                )
                is State.Abandon -> SuperwallEvent.TransactionAbandon(
                    product = state.product,
                    paywallInfo = paywallInfo
                )
                is State.Complete -> SuperwallEvent.TransactionComplete(
                    transaction = state.transaction,
                    product = state.product,
                    paywallInfo = paywallInfo
                )
                is State.Restore -> SuperwallEvent.TransactionRestore(paywallInfo = paywallInfo)
                is State.Timeout -> SuperwallEvent.TransactionTimeout(paywallInfo = paywallInfo)
            }
        override val rawName: String
            get() = superwallEvent.rawName

        override val canImplicitlyTriggerPaywall: Boolean
            get() = superwallEvent.canImplicitlyTriggerPaywall

        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return when (state) {
                is State.Start,
                is State.Abandon,
                is State.Complete,
                is State.Restore,
                is State.Timeout -> {
                    var eventParams = paywallInfo.eventParams(product).toMutableMap()
                    if (model != null) {
                        val json = Json { encodeDefaults = true }
                        // TODO: Figure out how to get this to work with kotlinx.serialization
                        val jsonObject: JsonObject =
                            json.encodeToJsonElement(model as GoogleBillingPurchaseTransaction).jsonObject

                        val modelMap: Map<String, Any> = jsonObject.mapValues { entry ->
                            when (val value = entry.value) {
                                is JsonPrimitive -> value.content // Handle other primitive types as needed
                                else -> value // Handle complex objects if necessary
                            }
                        }
                        eventParams.putAll(modelMap)
                    }
                    HashMap(eventParams)
                }
                is State.Fail -> {
                    when (state.error) {
                        is TransactionError.Failure,
                        is TransactionError.Pending -> {
                            val message = state.error.message
                            HashMap(
                                paywallInfo.eventParams(
                                    product,
                                    otherParams = mapOf("message" to message)
                                )
                            )
                        }
                    }
                }
            }
        }
    }


    class SubscriptionStart(
        val paywallInfo: PaywallInfo,
        val product: StoreProduct,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(
        SuperwallEvent.SubscriptionStart(
            product = product,
            paywallInfo = paywallInfo
        )
    ) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams(product))
        }
    }

    class FreeTrialStart(
        val paywallInfo: PaywallInfo,
        val product: StoreProduct,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(
        SuperwallEvent.FreeTrialStart(
            product = product,
            paywallInfo = paywallInfo
        )
    ) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams(product))
        }
    }

    class NonRecurringProductPurchase(
        val paywallInfo: PaywallInfo,
        val product: StoreProduct,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(
        SuperwallEvent.NonRecurringProductPurchase(
            product = TransactionProduct(
                product = product
            ), paywallInfo = paywallInfo
        )
    ) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams(product))
        }
    }


    class PaywallWebviewLoad(
        val state: State,
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(SuperwallEvent.PaywallWebviewLoadStart(paywallInfo)) {

        sealed class State {
            class Start : State()
            class Fail : State()
            class Timeout : State()
            class Complete : State()
        }

        override val superwallEvent: SuperwallEvent
            get() = when (state) {
                is PaywallWebviewLoad.State.Start -> SuperwallEvent.PaywallWebviewLoadStart(
                    paywallInfo
                )
                is PaywallWebviewLoad.State.Timeout -> SuperwallEvent.PaywallWebviewLoadTimeout(
                    paywallInfo
                )
                is PaywallWebviewLoad.State.Fail -> SuperwallEvent.PaywallWebviewLoadFail(
                    paywallInfo
                )
                is PaywallWebviewLoad.State.Complete -> SuperwallEvent.PaywallWebviewLoadComplete(
                    paywallInfo
                )
            }

        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams())
        }
    }

    class PaywallProductsLoad(
        val state: State,
        val paywallInfo: PaywallInfo,
        val eventData: EventData?,
        override var customParameters: HashMap<String, Any> = HashMap()
    ) : InternalSuperwallEvent(
        SuperwallEvent.PaywallProductsLoadStart(
            eventData?.name,
            paywallInfo
        )
    ) {

        sealed class State {
            class Start : State()
            class Fail : State()
            class Complete : State()
        }

        override val superwallEvent: SuperwallEvent
            get() = when (state) {
                is State.Start -> SuperwallEvent.PaywallProductsLoadStart(
                    eventData?.name,
                    paywallInfo
                )
                is State.Fail -> SuperwallEvent.PaywallProductsLoadFail(
                    eventData?.name,
                    paywallInfo
                )
                is State.Complete -> SuperwallEvent.PaywallProductsLoadComplete(
                    eventData?.name,
                    paywallInfo
                )
            }

        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            val fromEvent = eventData != null
            var params: HashMap<String, Any> = hashMapOf(
                "is_triggered_from_event" to fromEvent
            )
            params.putAll(paywallInfo.eventParams())
            return params
        }
    }


}

fun test() {
    InternalSuperwallEvent.AppOpen()
    InternalSuperwallEvent.AppInstall(appInstalledAtString = "test")
}