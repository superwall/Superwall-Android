package com.superwall.sdk.paywall.presentation

import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.models.config.FeatureGatingBehavior
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.StoreProduct
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant


@Serializable
data class PaywallInfo(
    val databaseId: String,
    val identifier: String,
    val name: String,
    val url: String,
    val experiment: Experiment?,
    val products: List<Product>,
    val productIds: List<String>,
    val presentedByEventWithName: String?,
    val presentedByEventWithId: String?,
    val presentedByEventAt: String?,
    val presentedBy: String,
    val responseLoadStartTime: String?,
    val responseLoadCompleteTime: String?,
    val responseLoadFailTime: String?,
    val responseLoadDuration: Double?,
    val webViewLoadStartTime: String?,
    val webViewLoadCompleteTime: String?,
    val webViewLoadFailTime: String?,
    val webViewLoadDuration: Double?,
    val productsLoadStartTime: String?,
    val productsLoadCompleteTime: String?,
    val productsLoadFailTime: String?,
    val productsLoadDuration: Double?,
    val paywalljsVersion: String?,
    val isFreeTrialAvailable: Boolean,
    val featureGatingBehavior: FeatureGatingBehavior,
    val closeReason: PaywallCloseReason?,
    val factory: TriggerSessionManagerFactory
) {
    constructor(
        databaseId: String,
        identifier: String,
        name: String,
        url: String,
        products: List<Product>,
        eventData: EventData?,
        responseLoadStartTime: Instant?,
        responseLoadCompleteTime: Instant?,
        responseLoadFailTime: Instant?,
        webViewLoadStartTime: Instant?,
        webViewLoadCompleteTime: Instant?,
        webViewLoadFailTime: Instant?,
        productsLoadStartTime: Instant?,
        productsLoadFailTime: Instant?,
        productsLoadCompleteTime: Instant?,
        experiment: Experiment? = null,
        paywalljsVersion: String? = null,
        isFreeTrialAvailable: Boolean,
        factory: TriggerSessionManagerFactory,
        featureGatingBehavior: FeatureGatingBehavior = FeatureGatingBehavior.NonGated,
        closeReason: PaywallCloseReason? = null
    ) : this(
        databaseId = databaseId,
        identifier = identifier,
        name = name,
        url = url,
        presentedByEventWithName = eventData?.name,
        presentedByEventAt = eventData?.createdAt?.toString(),
        presentedByEventWithId = eventData?.id?.lowercase(),
        experiment = experiment,
        paywalljsVersion = paywalljsVersion,
        products = products,
        productIds = products.map { it.id },
        isFreeTrialAvailable = isFreeTrialAvailable,
        featureGatingBehavior = featureGatingBehavior,
        presentedBy = eventData?.let { "event" } ?: "programmatically",
        responseLoadStartTime = responseLoadStartTime?.toString() ?: "",
        responseLoadCompleteTime = responseLoadStartTime?.toString() ?: "",
        responseLoadFailTime = responseLoadFailTime?.toString() ?: "",
        responseLoadDuration = responseLoadStartTime?.let { startTime ->
            responseLoadCompleteTime?.let { endTime ->
                (endTime.epochSecond - startTime.epochSecond).toDouble()
            }
        },
        webViewLoadStartTime = webViewLoadStartTime?.toString() ?: "",
        webViewLoadCompleteTime = webViewLoadCompleteTime?.toString() ?: "",
        webViewLoadFailTime = webViewLoadFailTime?.toString() ?: "",
        webViewLoadDuration = webViewLoadStartTime?.let { startTime ->
            webViewLoadCompleteTime?.let { endTime ->
                (endTime.epochSecond - startTime.epochSecond).toDouble()
            }
        },
        productsLoadStartTime = productsLoadStartTime?.toString() ?: "",
        productsLoadCompleteTime = productsLoadCompleteTime?.toString() ?: "",
        productsLoadFailTime = productsLoadFailTime?.toString() ?: "",
        productsLoadDuration = productsLoadStartTime?.let { startTime ->
            productsLoadCompleteTime?.let { endTime ->
                (endTime.epochSecond - startTime.epochSecond).toDouble()
            }
        },
        factory = factory,
        closeReason = closeReason
    )

    suspend fun eventParams(
        product: StoreProduct? = null,
        otherParams: Map<String, Any>? = null
    ): Map<String, Any> {
        val output = mutableMapOf<String, Any>(
            "paywall_id" to databaseId,
            ("paywalljs_version" to paywalljsVersion ?: "") as Pair<String, Any>,
            "paywall_identifier" to identifier,
            "paywall_name" to name,
            "paywall_url" to url.toString(),
            "presented_by_event_name" to presentedByEventWithName as Any,
            "presented_by_event_id" to presentedByEventWithId as Any,
            "presented_by_event_timestamp" to presentedByEventAt as Any,
            "presented_by" to presentedBy,
            "paywall_product_ids" to productIds.joinToString(","),
            "paywall_response_load_start_time" to responseLoadStartTime as Any,
            "paywall_response_load_complete_time" to responseLoadCompleteTime as Any,
            "paywall_response_load_duration" to responseLoadDuration as Any,
            "paywall_webview_load_start_time" to webViewLoadStartTime as Any,
            "paywall_webview_load_complete_time" to webViewLoadCompleteTime as Any,
            "paywall_webview_load_duration" to webViewLoadDuration as Any,
            "paywall_products_load_start_time" to productsLoadStartTime as Any,
            "paywall_products_load_complete_time" to productsLoadCompleteTime as Any,
            "paywall_products_load_fail_time" to productsLoadFailTime as Any,
            "paywall_products_load_duration" to productsLoadDuration as Any,
            "is_free_trial_available" to isFreeTrialAvailable,
            "feature_gating" to featureGatingBehavior.toString()
        )

        // TODO: Re-enable this
//        val triggerSessionManager = factory.getTriggerSessionManager()
//        val triggerSession = triggerSessionManager.activeTriggerSession
//
//        if (triggerSession?.paywall?.databaseId == this.databaseId) {
//            output["trigger_session_id"] = triggerSession.id
//            output["experiment_id"] = triggerSession.trigger.experiment?.id
//            output["variant_id"] = triggerSession.trigger.experiment?.variant?.id
//        }

        val loadingVars = mutableMapOf<String, Any>()
        for (key in output.keys) {
            if (key.contains("_load_")) {
                output[key]?.let {
                    loadingVars[key] = it
                }
            }
        }

        Logger.debug(
            logLevel = LogLevel.debug,
            scope = LogScope.paywallEvents,
            message = "Paywall loading timestamps",
            info = loadingVars
        )

        val levels = listOf("primary", "secondary", "tertiary")

        for ((id, level) in levels.withIndex()) {
            val key = "${level}_product_id"
            output[key] = ""
            if (id < products.size) {
                output[key] = productIds[id]
            }
        }

        // TODO: Re-enable store product
//        product?.let {
//            output["product_id"] = it.productIdentifier
//            for (key in it.attributes.keys) {
//                it.attributes[key]?.let { value ->
//                    output["product_${key.camelCaseToSnakeCase()}"] = value
//                }
//            }
//        }

        otherParams?.let {
            for (key in it.keys) {
                it[key]?.let { value ->
                    output[key] = value
                }
            }
        }

        return output
    }

}
