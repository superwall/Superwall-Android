package com.superwall.sdk.paywall.presentation

import LogLevel
import LogScope
import Logger
import com.superwall.sdk.config.models.Survey
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.misc.camelCaseToSnakeCase
import com.superwall.sdk.models.config.FeatureGatingBehavior
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.serialization.URLSerializer
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.store.abstractions.product.StoreProduct
import kotlinx.serialization.Serializable
import java.net.URL
import java.util.*


@Serializable
data class PaywallInfo(
    val databaseId: String,
    val identifier: String,
    val name: String,
    @Serializable(with = URLSerializer::class)
    val url: URL,
    val experiment: Experiment?,
    val triggerSessionId: String?,
    val products: List<Product>,
    val productIds: List<String>,
    val presentedByEventWithName: String?,
    val presentedByEventWithId: String?,
    val presentedByEventAt: String?,
    val presentedBy: String,
    val presentationSourceType: String?,
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
    val surveys: List<Survey>,
    val factory: TriggerSessionManagerFactory
) {
    constructor(
        databaseId: String,
        identifier: String,
        name: String,
        url: URL,
        products: List<Product>,
        eventData: EventData?,
        responseLoadStartTime: Date?,
        responseLoadCompleteTime: Date?,
        responseLoadFailTime: Date?,
        webViewLoadStartTime: Date?,
        webViewLoadCompleteTime: Date?,
        webViewLoadFailTime: Date?,
        productsLoadStartTime: Date?,
        productsLoadFailTime: Date?,
        productsLoadCompleteTime: Date?,
        experiment: Experiment? = null,
        triggerSessionId: String? = null,
        paywalljsVersion: String? = null,
        isFreeTrialAvailable: Boolean,
        presentationSourceType: String? = null,
        factory: TriggerSessionManagerFactory,
        featureGatingBehavior: FeatureGatingBehavior = FeatureGatingBehavior.NonGated,
        closeReason: PaywallCloseReason? = null,
        surveys: List<Survey>
    ) : this(
        databaseId = databaseId,
        identifier = identifier,
        name = name,
        url = url,
        presentedByEventWithName = eventData?.name,
        presentedByEventAt = eventData?.createdAt?.toString(),
        presentedByEventWithId = eventData?.id?.lowercase(),
        experiment = experiment,
        triggerSessionId = triggerSessionId,
        paywalljsVersion = paywalljsVersion,
        products = products,
        productIds = products.map { it.id },
        isFreeTrialAvailable = isFreeTrialAvailable,
        featureGatingBehavior = featureGatingBehavior,
        presentedBy = eventData?.let { "event" } ?: "programmatically",
        presentationSourceType = presentationSourceType,
        responseLoadStartTime = responseLoadStartTime?.toString() ?: "",
        responseLoadCompleteTime = responseLoadStartTime?.toString() ?: "",
        responseLoadFailTime = responseLoadFailTime?.toString() ?: "",
        responseLoadDuration = responseLoadStartTime?.let { startTime ->
            responseLoadCompleteTime?.let { endTime ->
                (endTime.time / 1000 - startTime.time / 1000).toDouble()
            }
        },
        webViewLoadStartTime = webViewLoadStartTime?.toString() ?: "",
        webViewLoadCompleteTime = webViewLoadCompleteTime?.toString() ?: "",
        webViewLoadFailTime = webViewLoadFailTime?.toString() ?: "",
        webViewLoadDuration = webViewLoadStartTime?.let { startTime ->
            webViewLoadCompleteTime?.let { endTime ->
                (endTime.time / 1000 - startTime.time / 1000).toDouble()
            }
        },
        productsLoadStartTime = productsLoadStartTime?.toString() ?: "",
        productsLoadCompleteTime = productsLoadCompleteTime?.toString() ?: "",
        productsLoadFailTime = productsLoadFailTime?.toString() ?: "",
        productsLoadDuration = productsLoadStartTime?.let { startTime ->
            productsLoadCompleteTime?.let { endTime ->
                (endTime.time / 1000 - startTime.time / 1000).toDouble()
            }
        },
        factory = factory,
        closeReason = closeReason,
        surveys = surveys
    )

    fun eventParams(
        product: StoreProduct? = null,
        otherParams: Map<String, Any?>? = null
    ): Map<String, Any> {
        var output = customParams()

        val params = mutableMapOf(
            (("paywalljs_version" to paywalljsVersion) ?: "") as Pair<String, Any>,
            "paywall_identifier" to identifier,
            "paywall_url" to url.toString(),
            "presented_by_event_id" to presentedByEventWithId,
            "presented_by_event_timestamp" to presentedByEventAt,
            "presentation_source_type" to presentationSourceType,
            "paywall_response_load_start_time" to responseLoadStartTime,
            "paywall_response_load_complete_time" to responseLoadCompleteTime,
            "paywall_response_load_duration" to responseLoadDuration,
            "paywall_webview_load_start_time" to webViewLoadStartTime,
            "paywall_webview_load_complete_time" to webViewLoadCompleteTime,
            "paywall_webview_load_duration" to webViewLoadDuration,
            "paywall_products_load_start_time" to productsLoadStartTime,
            "paywall_products_load_complete_time" to productsLoadCompleteTime,
            "paywall_products_load_fail_time" to productsLoadFailTime,
            "paywall_products_load_duration" to productsLoadDuration,
            "trigger_session_id" to triggerSessionId,
            "experiment_id" to experiment?.id,
            "variant_id" to experiment?.variant?.id
        )
        params.values.removeAll { it == null }
        val filteredParams = params as MutableMap<String, Any>
        output.putAll(filteredParams)

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

        product?.let {
            output["product_id"] = it.productIdentifier
            for (key in it.attributes.keys) {
                it.attributes[key]?.let { value ->
                    output["product_${key.camelCaseToSnakeCase()}"] = value
                }
            }
        }

        otherParams?.let {
            for (key in it.keys) {
                it[key]?.let { value ->
                    output[key] = value
                }
            }
        }

        return output
    }

    /// Parameters that can be used in rules.
    fun customParams(): MutableMap<String, Any> {
        val output: MutableMap<String, Any?> = mutableMapOf(
            "paywall_id" to databaseId,
            "paywall_name" to name,
            "presented_by_event_name" to (presentedByEventWithName ?: ""),
            "paywall_product_ids" to productIds.joinToString(","),
            "is_free_trial_available" to isFreeTrialAvailable,
            "feature_gating" to featureGatingBehavior.toString(),
            "presented_by" to presentedBy
        )

        val levels = listOf("primary", "secondary", "tertiary")

        for ((id, level) in levels.withIndex()) {
            val key = "${level}_product_id"
            output[key] = ""
            if (id < products.size) {
                output[key] = productIds[id]
            }
        }

        return output.filter { (_, value) -> value != null } as MutableMap<String, Any>
    }
}
