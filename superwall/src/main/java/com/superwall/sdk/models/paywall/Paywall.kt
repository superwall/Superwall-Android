package com.superwall.sdk.models.paywall

import ComputedPropertyRequest
import com.superwall.sdk.config.models.Survey
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.models.SerializableEntity
import com.superwall.sdk.models.config.FeatureGatingBehavior
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductVariable
import com.superwall.sdk.models.serialization.DateSerializer
import com.superwall.sdk.models.serialization.URLSerializer
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URL
import java.util.*

@Serializable
data class Paywalls(val paywalls: List<Paywall>)

@Serializable
data class Paywall(
    @SerialName("id")
    val databaseId: String,
    var identifier: String,
    val name: String,
    val url: @Serializable(with = URLSerializer::class) URL,
    @SerialName("paywalljs_event")
    val htmlSubstitutions: String,
    @kotlinx.serialization.Transient()
    var presentation: Presentation = Presentation(
        PaywallPresentationStyle.MODAL,
        PresentationCondition.ALWAYS
    ),

    @SerialName("presentation_style_v2")
    private val presensentationStyle: String,

    private val presentationCondition: String,

    val backgroundColorHex: String,

    var products: List<Product>,
    @kotlinx.serialization.Transient()
    var productIds: List<String> = arrayListOf(),
    @kotlinx.serialization.Transient()
    var responseLoadingInfo: LoadingInfo = LoadingInfo(),

    @kotlinx.serialization.Transient()
    var webviewLoadingInfo: LoadingInfo = LoadingInfo(),
    @kotlinx.serialization.Transient()
    var productsLoadingInfo: LoadingInfo = LoadingInfo(),
    var productVariables: List<ProductVariable>? = null,
    var swProductVariablesTemplate: List<ProductVariable>? = null,
    var paywalljsVersion: String? = null,
    var isFreeTrialAvailable: Boolean = false,

    /// The source of the presentation request. Either 'implicit', 'getPaywall', 'register'.
    var presentationSourceType: String? = null,

    var featureGating: FeatureGatingBehavior = FeatureGatingBehavior.NonGated,

    @SerialName("computedProperties")
    var computedPropertyRequests: List<ComputedPropertyRequest> = emptyList(),

    @kotlinx.serialization.Transient()
    var experiment: Experiment? = null,

    @kotlinx.serialization.Transient()
    var closeReason: PaywallCloseReason = PaywallCloseReason.None,

    /**
     Surveys to potentially show when an action happens in the paywall.
     */
    var surveys: List<Survey> = emptyList()

) : SerializableEntity {
    init {
        productIds = products.map { it.id }
        presentation = Presentation(
            style = PaywallPresentationStyle.valueOf(presensentationStyle.uppercase()),
            condition = PresentationCondition.valueOf(presentationCondition.uppercase())
        )
    }

    @Serializable
    data class Presentation(
        val style: PaywallPresentationStyle,
        val condition: PresentationCondition
    )

    @Serializable
    data class LoadingInfo(
        @Serializable(with = DateSerializer::class)
        var startAt: Date? = null,
        @Serializable(with = DateSerializer::class)
        var endAt: Date? = null,
        @Serializable(with = DateSerializer::class)
        var failAt: Date? = null
    )


//    @Serializable
//    data class ProductVariable(val type: String, val attributes: Map<String, String>)


    fun update(paywall: Paywall) {
        products = paywall.products
        productIds = paywall.productIds
        // TODO: Figure out if the products makes sense like this
//        swProducts = paywall.swProducts
        productVariables = paywall.productVariables
        swProductVariablesTemplate = paywall.swProductVariablesTemplate
        isFreeTrialAvailable = paywall.isFreeTrialAvailable
        productsLoadingInfo = paywall.productsLoadingInfo
        presentationSourceType = paywall.presentationSourceType
        experiment = paywall.experiment
    }

    fun getInfo(fromEvent: EventData?, factory: TriggerSessionManagerFactory): PaywallInfo {
        return PaywallInfo(
            databaseId = databaseId,
            identifier = identifier,
            name = name,
            url = url,
            products = products,
            eventData = fromEvent,
            responseLoadStartTime = responseLoadingInfo.startAt,
            responseLoadCompleteTime = responseLoadingInfo.endAt,
            responseLoadFailTime = responseLoadingInfo.failAt,
            webViewLoadStartTime = webviewLoadingInfo.startAt,
            webViewLoadCompleteTime = webviewLoadingInfo.endAt,
            webViewLoadFailTime = webviewLoadingInfo.failAt,
            productsLoadStartTime = productsLoadingInfo.startAt,
            productsLoadFailTime = productsLoadingInfo.failAt,
            productsLoadCompleteTime = productsLoadingInfo.endAt,
            experiment = experiment,
            paywalljsVersion = paywalljsVersion,
            isFreeTrialAvailable = isFreeTrialAvailable,
            factory = factory,
            featureGatingBehavior = featureGating,
            closeReason = closeReason,
            surveys = surveys
        )
    }


    companion object {
        fun stub(): Paywall {
            return Paywall(
                databaseId = "id",
                identifier = "identifier",
                name = "abac",
                url = URL("https://google.com"),
                htmlSubstitutions = "",
                presentation = Presentation(
                    PaywallPresentationStyle.MODAL,
                    PresentationCondition.CHECK_USER_SUBSCRIPTION
                ),
                presensentationStyle = "MODAL",
                presentationCondition = "CHECK_USER_SUBSCRIPTION",
                backgroundColorHex = "000000",
                products = arrayListOf(),
                productIds = arrayListOf(),
                responseLoadingInfo = LoadingInfo(),
                webviewLoadingInfo = LoadingInfo(),
                productsLoadingInfo = LoadingInfo(),
                productVariables = arrayListOf(),
                swProductVariablesTemplate = arrayListOf(),
                paywalljsVersion = "",
                isFreeTrialAvailable = false,
                featureGating = FeatureGatingBehavior.NonGated
            )
        }
    }
}
