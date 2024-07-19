package com.superwall.sdk.models.paywall

import ComputedPropertyRequest
import android.graphics.Color
import com.superwall.sdk.config.models.OnDeviceCaching
import com.superwall.sdk.config.models.Survey
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.SerializableEntity
import com.superwall.sdk.models.config.FeatureGatingBehavior
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.product.Product
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.models.product.ProductItemsDeserializer
import com.superwall.sdk.models.product.ProductType
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
data class Paywalls(
    val paywalls: List<Paywall>,
) : SerializableEntity

@Serializable
data class Paywall(
    @SerialName("id")
    val databaseId: String,
    var identifier: String,
    val name: String,
    val url:
        @Serializable(with = URLSerializer::class)
        URL,
    @SerialName("paywalljs_event")
    val htmlSubstitutions: String,
    @SerialName("presentation_style_v2")
    private val presentationStyle: String,
    @SerialName("presentation_delay")
    private val presentationDelay: Long,
    private val presentationCondition: String,
    @kotlinx.serialization.Transient()
    var presentation: PaywallPresentationInfo =
        PaywallPresentationInfo(
            style = PaywallPresentationStyle.valueOf(presentationStyle.uppercase()),
            condition = PresentationCondition.valueOf(presentationCondition.uppercase()),
            delay = presentationDelay,
        ),
    val backgroundColorHex: String,
    val darkBackgroundColorHex: String? = null,
    // Declared as private to prevent direct access
    @kotlinx.serialization.Transient()
    private var _products: List<Product> = emptyList(),
    @Serializable(with = ProductItemsDeserializer::class)
    @SerialName("products_v2")
    private var _productItems: List<ProductItem>,
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
    // / The source of the presentation request. Either 'implicit', 'getPaywall', 'register'.
    var presentationSourceType: String? = null,
    var featureGating: FeatureGatingBehavior = FeatureGatingBehavior.NonGated,
    @SerialName("computed_properties")
    var computedPropertyRequests: List<ComputedPropertyRequest> = emptyList(),
    var localNotifications: List<LocalNotification> = emptyList(),
    /**
     * Indicates whether the caching of the paywall is enabled or not.
     */
    var onDeviceCache: OnDeviceCaching = OnDeviceCaching.Disabled,
    @kotlinx.serialization.Transient()
    var experiment: Experiment? = null,
    @kotlinx.serialization.Transient()
    var closeReason: PaywallCloseReason = PaywallCloseReason.None,
    /**
     Surveys to potentially show when an action happens in the paywall.
     */
    var surveys: List<Survey> = emptyList(),
) : SerializableEntity {
    // Public getter for productItems
    var productItems: List<ProductItem>
        get() = _productItems
        set(value) {
            _productItems = value
            // Automatically update related properties when productItems is set
            productIds = value.map { it.fullProductId }
            _products = makeProducts(value) // Assuming makeProducts is a function that generates products based on product items
        }

    // Public getter for products to allow access but not direct modification
    val products: List<Product>
        get() = _products

    val backgroundColor: Int by lazy {
        try {
            Color.parseColor(this.backgroundColorHex)
        } catch (e: Throwable) {
            Logger.debug(
                logLevel = LogLevel.warn,
                scope = LogScope.paywallView,
                message =
                    "Invalid paywall background color: ${this.backgroundColorHex}. " +
                        "Defaulting to white.",
            )
            Color.WHITE
        }
    }

    val darkBackgroundColor: Int? by lazy {
        try {
            Color.parseColor(this.darkBackgroundColorHex)
        } catch (e: Throwable) {
            Logger.debug(
                logLevel = LogLevel.warn,
                scope = LogScope.paywallView,
                message =
                    "Invalid paywall background color: ${this.darkBackgroundColorHex}. " +
                        "Defaulting to white.",
            )
            null
        }
    }

    init {
        productItems = _productItems
    }

    @Serializable
    data class LoadingInfo(
        @Serializable(with = DateSerializer::class)
        var startAt: Date? = null,
        @Serializable(with = DateSerializer::class)
        var endAt: Date? = null,
        @Serializable(with = DateSerializer::class)
        var failAt: Date? = null,
    )

    fun update(paywall: Paywall) {
        productItems = paywall.productItems
        productVariables = paywall.productVariables
        swProductVariablesTemplate = paywall.swProductVariablesTemplate
        isFreeTrialAvailable = paywall.isFreeTrialAvailable
        productsLoadingInfo = paywall.productsLoadingInfo
        presentationSourceType = paywall.presentationSourceType
        experiment = paywall.experiment
    }

    fun getInfo(fromEvent: EventData?): PaywallInfo =
        PaywallInfo(
            databaseId = databaseId,
            identifier = identifier,
            name = name,
            url = url,
            products = products,
            productIds = productIds,
            productItems = productItems,
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
            presentationSourceType = presentationSourceType,
            featureGatingBehavior = featureGating,
            closeReason = closeReason,
            localNotifications = localNotifications,
            computedPropertyRequests = computedPropertyRequests,
            surveys = surveys,
            presentation = presentation,
        )

    companion object {
        private fun makeProducts(productItems: List<ProductItem>): List<Product> {
            val output = mutableListOf<Product>()

            for (productItem in productItems) {
                when (productItem.name) {
                    "primary" ->
                        output.add(
                            Product(type = ProductType.PRIMARY, id = productItem.fullProductId),
                        )
                    "secondary" ->
                        output.add(
                            Product(type = ProductType.SECONDARY, id = productItem.fullProductId),
                        )
                    "tertiary" ->
                        output.add(
                            Product(type = ProductType.TERTIARY, id = productItem.fullProductId),
                        )
                }
            }

            return output
        }

        fun stub(): Paywall =
            Paywall(
                databaseId = "id",
                identifier = "identifier",
                name = "abac",
                url = URL("https://google.com"),
                htmlSubstitutions = "",
                presentation =
                    PaywallPresentationInfo(
                        PaywallPresentationStyle.MODAL,
                        PresentationCondition.CHECK_USER_SUBSCRIPTION,
                        300,
                    ),
                presentationStyle = "MODAL",
                presentationCondition = "CHECK_USER_SUBSCRIPTION",
                backgroundColorHex = "000000",
                darkBackgroundColorHex = null,
                productIds = arrayListOf(),
                _productItems = emptyList(),
                _products = emptyList(),
                responseLoadingInfo = LoadingInfo(),
                webviewLoadingInfo = LoadingInfo(),
                productsLoadingInfo = LoadingInfo(),
                productVariables = arrayListOf(),
                swProductVariablesTemplate = arrayListOf(),
                paywalljsVersion = "",
                isFreeTrialAvailable = false,
                featureGating = FeatureGatingBehavior.NonGated,
                localNotifications = arrayListOf(),
                presentationDelay = 300,
            )
    }
}
