package com.superwall.sdk.models.paywall

import ComputedPropertyRequest
import android.graphics.Color
import com.superwall.sdk.config.models.OnDeviceCaching
import com.superwall.sdk.config.models.Survey
import com.superwall.sdk.dependencies.TriggerSessionManagerFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.models.SerializableEntity
import com.superwall.sdk.models.config.FeatureGatingBehavior
import com.superwall.sdk.models.config.WebArchiveManifest
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
import java.util.Date

@Serializable
data class Paywalls(val paywalls: List<Paywall>) : SerializableEntity

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
    private val presentationStyle: String,

    private val presentationCondition: String,

    val backgroundColorHex: String,

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

    /// The source of the presentation request. Either 'implicit', 'getPaywall', 'register'.
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
    var triggerSessionId: String? = null,

    @kotlinx.serialization.Transient()
    var closeReason: PaywallCloseReason = PaywallCloseReason.None,

    /**
    Surveys to potentially show when an action happens in the paywall.
     */
    var surveys: List<Survey> = emptyList(),

    // Manifest for webarchive files
    @SerialName("manifest") val manifest: WebArchiveManifest? = null,


    ) : SerializableEntity {
    // Public getter for productItems
    var productItems: List<ProductItem>
        get() = _productItems
        set(value) {
            _productItems = value
            // Automatically update related properties when productItems is set
            productIds = value.map { it.fullProductId }
            _products =
                makeProducts(value) // Assuming makeProducts is a function that generates products based on product items
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
                scope = LogScope.paywallViewController,
                message = "Invalid paywall background color: ${this.backgroundColorHex}. " +
                        "Defaulting to white."
            )
            Color.WHITE
        }
    }

    init {
        productItems = _productItems
        presentation = Presentation(
            style = PaywallPresentationStyle.valueOf(presentationStyle.uppercase()),
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

    fun update(paywall: Paywall) {
        productItems = paywall.productItems
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
            triggerSessionId = triggerSessionId,
            paywalljsVersion = paywalljsVersion,
            isFreeTrialAvailable = isFreeTrialAvailable,
            presentationSourceType = presentationSourceType,
            factory = factory,
            featureGatingBehavior = featureGating,
            closeReason = closeReason,
            localNotifications = localNotifications,
            computedPropertyRequests = computedPropertyRequests,
            surveys = surveys
        )
    }

    companion object {
        private fun makeProducts(productItems: List<ProductItem>): List<Product> {
            val output = mutableListOf<Product>()

            for (productItem in productItems) {
                when (productItem.name) {
                    "primary" -> output.add(
                        Product(type = ProductType.PRIMARY, id = productItem.fullProductId)
                    )

                    "secondary" -> output.add(
                        Product(type = ProductType.SECONDARY, id = productItem.fullProductId)
                    )

                    "tertiary" -> output.add(
                        Product(type = ProductType.TERTIARY, id = productItem.fullProductId)
                    )
                }
            }

            return output
        }

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
                presentationStyle = "MODAL",
                presentationCondition = "CHECK_USER_SUBSCRIPTION",
                backgroundColorHex = "000000",
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
                manifest = WebArchiveManifest(
                    WebArchiveManifest.Usage.ALWAYS,
                    WebArchiveManifest.Document(URL("http://google.com"), "text/html"),
                    emptyList()
                )
            )

        }
    }
}
