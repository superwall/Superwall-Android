package com.superwall.sdk.analytics.internal.trackable

import com.superwall.sdk.analytics.SessionEventsManager
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerResult
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatus
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.storage.keys.SubscriptionStatus
import java.net.URL
import java.util.*
import kotlin.collections.HashMap

interface TrackableSuperwallEvent: Trackable {
    val superwallEvent: SuperwallEvent
}

sealed class InternalSuperwallEvent(override val superwallEvent: SuperwallEvent): TrackableSuperwallEvent {

    override val rawName: String
        get() = this.superwallEvent.rawName

    override val canImplicitlyTriggerPaywall: Boolean
        get() = this.superwallEvent.canImplicitlyTriggerPaywall

    class AppOpen(override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.AppOpen()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap()
        }
    }

    class AppInstall(val appInstalledAtString: String, override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.AppInstall()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "application_installed_at" to appInstalledAtString
            )
        }
    }

   // TODO: Implement the reste

    class AppLaunch(override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.AppLaunch()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> = HashMap()
    }

    class Attributes(val appInstalledAtString: String, override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.UserAttributes(customParameters)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "application_installed_at" to appInstalledAtString
            )
        }
    }

    class DeepLink(val url: URL, override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.DeepLink(url)) {
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

    class FirstSeen(override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.FirstSeen()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> = HashMap()
    }

    class AppClose(override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.AppClose()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> = HashMap()
    }



    class PaywallLoad(val state: State, val eventData: EventData?, override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.PaywallResponseLoadStart(eventData?.name)) {

        sealed class State() {
            class Start(): State()
            class NotFound(): State()
            class Fail(): State()
            class Complete(val paywallInfo: PaywallInfo): State()
        }


        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            val fromEvent = eventData != null
            val params: HashMap<String, Any> = hashMapOf(
                "is_triggered_from_event" to fromEvent
            )

            when(state) {
                is State.Start -> return params
                is State.NotFound -> return params
                is State.Fail -> return params
                is State.Complete -> return  HashMap(state.paywallInfo.eventParams(otherParams = params).filterValues { it != null } as Map<String, Any>)
            }

        }
    }

    class SubscriptionStatusDidChange(val subscriptionStatus: SubscriptionStatus, override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.SubscriptionStatusDidChange()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return hashMapOf(
                "subscription_status" to subscriptionStatus.description
            )
        }
    }


    class SessionStart(override var customParameters: HashMap<String, Any> = HashMap()): InternalSuperwallEvent(SuperwallEvent.SessionStart()) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap()
        }
    }

    class TriggerFire(
        val triggerResult: TriggerResult,
        val triggerName: String,
        override var customParameters: HashMap<String, Any> = HashMap(),
        private val sessionEventsManager: SessionEventsManager
    ): InternalSuperwallEvent(SuperwallEvent.TriggerFire(eventName = triggerName, result = triggerResult)) {
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
                        this["paywall_identifier"] = triggerResult.experiment.variant.paywallId ?: ""
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
    ): InternalSuperwallEvent(SuperwallEvent.PaywallPresentationRequest(status = status, reason = statusReason)) {
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
    ): InternalSuperwallEvent(SuperwallEvent.PaywallOpen(paywallInfo = paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return  HashMap( paywallInfo.eventParams())
        }
    }

    class PaywallClose(
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ): InternalSuperwallEvent(SuperwallEvent.PaywallClose(paywallInfo = paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams())
        }
    }

    class PaywallDecline(
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ): InternalSuperwallEvent(SuperwallEvent.PaywallDecline(paywallInfo = paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return HashMap(paywallInfo.eventParams())
        }
    }

    /*
    sealed class Transaction(
        val paywallInfo: PaywallInfo,
        val product: StoreProduct?,
        val model: StoreTransaction?,
        override var customParameters: HashMap<String, Any> = HashMap()
    ): InternalSuperwallEvent(SuperwallEvent.TransactionStart(product, paywallInfo)) {

        class Start(product: StoreProduct) : Transaction(product, product, null)
        class Fail(error: TransactionError) : Transaction(null, null, null)
        class Abandon(product: StoreProduct) : Transaction(product, product, null)
        class Complete(product: StoreProduct, model: StoreTransaction) : Transaction(product, product, model)
        class Restore : Transaction(null, null, null)
        class Timeout : Transaction(null, null, null)

        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return when (this) {
                is Start, is Abandon, is Complete, is Restore, is Timeout -> {
                    var eventParams = paywallInfo.eventParams(forProduct = product)
                    model?.let {
                        eventParams.putAll(it.dictionary(withSnakeCase = true))
                    }
                    eventParams
                }
                is Fail -> {
                    when (val error = this.error) {
                        is TransactionError.Failure -> {
                            paywallInfo.eventParams(forProduct = product, otherParams = hashMapOf("message" to error.message))
                        }
                        is TransactionError.Pending -> {
                            paywallInfo.eventParams(forProduct = product, otherParams = hashMapOf("message" to error.message))
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
    ): InternalSuperwallEvent(SuperwallEvent.SubscriptionStart(product, paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return paywallInfo.eventParams(forProduct = product)
        }
    }

    class FreeTrialStart(
        val paywallInfo: PaywallInfo,
        val product: StoreProduct,
        override var customParameters: HashMap<String, Any> = HashMap()
    ): InternalSuperwallEvent(SuperwallEvent.FreeTrialStart(product, paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return paywallInfo.eventParams(forProduct = product)
        }
    }

    class NonRecurringProductPurchase(
        val paywallInfo: PaywallInfo,
        val product: StoreProduct,
        override var customParameters: HashMap<String, Any> = HashMap()
    ): InternalSuperwallEvent(SuperwallEvent.NonRecurringProductPurchase(Product(product), paywallInfo)) {
        override suspend fun getSuperwallParameters(): HashMap<String, Any> {
            return paywallInfo.eventParams(forProduct = product)
        }
    }

    */


        class PaywallWebviewLoad(
        val state: State,
        val paywallInfo: PaywallInfo,
        override var customParameters: HashMap<String, Any> = HashMap()
    ): InternalSuperwallEvent(SuperwallEvent.PaywallWebviewLoadStart(paywallInfo)) {

        sealed class State {
            class Start : State()
            class Fail : State()
            class Timeout : State()
            class Complete : State()
        }

        override val superwallEvent: SuperwallEvent
            get() = when (state) {
                is PaywallWebviewLoad.State.Start -> SuperwallEvent.PaywallWebviewLoadStart(paywallInfo)
                is PaywallWebviewLoad.State.Timeout -> SuperwallEvent.PaywallWebviewLoadTimeout(paywallInfo)
                is PaywallWebviewLoad.State.Fail -> SuperwallEvent.PaywallWebviewLoadFail(paywallInfo)
                is PaywallWebviewLoad.State.Complete -> SuperwallEvent.PaywallWebviewLoadComplete(paywallInfo)
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
    ): InternalSuperwallEvent(SuperwallEvent.PaywallProductsLoadStart(eventData?.name, paywallInfo)) {

        sealed class State {
            class Start : State()
            class Fail : State()
            class Complete : State()
        }

        override val superwallEvent: SuperwallEvent
            get() = when (state) {
                is State.Start -> SuperwallEvent.PaywallProductsLoadStart(eventData?.name, paywallInfo)
                is State.Fail -> SuperwallEvent.PaywallProductsLoadFail(eventData?.name, paywallInfo)
                is State.Complete -> SuperwallEvent.PaywallProductsLoadComplete(eventData?.name, paywallInfo)
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