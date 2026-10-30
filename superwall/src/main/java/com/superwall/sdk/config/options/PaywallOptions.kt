package com.superwall.sdk.config.options

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.superwall.sdk.R
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.paywall.presentation.PaywallInfo
import kotlin.time.Duration

/** Options for configuring the appearance and behavior of paywalls. */
class PaywallOptions() {
    constructor(definition: PaywallOptions.() -> Unit) : this() {
        definition()
    }

    /**
     * Determines whether the paywall should use haptic feedback. Defaults to true.
     *
     * Haptic feedback occurs when a user purchases or restores a product, opens a URL
     * from the paywall, or closes the paywall.
     */
    var isHapticFeedbackEnabled: Boolean = true

    /**
     * Defines the messaging of the alert presented to the user when restoring a transaction fails.
     *
     * Values left at their defaults are shown in the device's language.
     */
    class RestoreFailed() {
        constructor(definition: RestoreFailed.() -> Unit) : this() {
            definition()
        }

        /**
         * The title of the alert presented to the user when restoring a transaction fails. Defaults to
         * a localized `No Subscription Found`.
         */
        var title: String = DEFAULT_TITLE

        /**
         * Defines the message of the alert presented to the user when restoring a transaction fails.
         * Defaults to a localized `We couldn't find an active subscription for your account.`
         */
        var message: String = DEFAULT_MESSAGE

        /**
         * Defines the title of the close button in the alert presented to the user when restoring a
         * transaction fails. Defaults to a localized `Okay`.
         */
        var closeButtonTitle: String = DEFAULT_CLOSE_BUTTON_TITLE

        internal companion object {
            const val DEFAULT_TITLE = "No Subscription Found"
            const val DEFAULT_MESSAGE = "We couldn't find an active subscription for your account."
            const val DEFAULT_CLOSE_BUTTON_TITLE = "Okay"
        }
    }

    /** Defines the messaging of the alert presented to the user when restoring a transaction fails. */
    var restoreFailed: RestoreFailed = RestoreFailed()

    /**
     * Shows an alert after a purchase fails. Defaults to `true`.
     *
     * Set this to `false` if you're using a `PurchaseController` and want to show
     * your own alert after the purchase fails.
     */
    var shouldShowPurchaseFailureAlert = true

    /**
     * Pre-loads and caches trigger paywalls and products when you initialize the SDK via
     * ``Superwall/configure(apiKey:purchaseController:options:completion:)-52tke``. Defaults to `true`.
     *
     * Set this to `false` to load and cache paywalls and products in a just-in-time fashion.
     *
     * If you want to preload them at a later date, you can call ``Superwall/preloadAllPaywalls()``
     * or ``Superwall/preloadPaywalls(forEvents:)``
     */
    var shouldPreload: Boolean = true

    /**
     * If preloading is enabled, you can control preloading per-device tier.
     * This way you can control preloading on low-end and high-end devices granularly.
     * Define only the tiers you want to specifically control, the rest will fall back
     * to using the [shouldPreload] flag.
     *
     * Device tiers can be one of:
     * - [Tier.ULTRA_LOW]: ≤2GB RAM, ~1.2GHz quad-core, missing modern codecs
     *    (e.g. Android Go devices, older budget phones)
     * - [Tier.LOW]: 3–4GB RAM, ~1.8GHz 4–8 cores, partial codec support
     *    (e.g. entry-level Galaxy A1x/A2x)
     * - [Tier.MID]: 4–6GB RAM, ~2.0–2.4GHz 8 cores, full codec support
     *    (e.g. Pixel 6a, Galaxy A5x)
     * - [Tier.HIGH]: 6–8GB RAM, ~2.4–2.8GHz 8 cores, high-density display
     *    (e.g. Pixel 7, Galaxy S22)
     * - [Tier.ULTRA_HIGH]: ≥10GB RAM, 2.8+GHz with prime cores ≥3.5GHz
     *    (e.g. Pixel 8 Pro, Galaxy S24 Ultra)
     * - [Tier.UNKNOWN]: device info could not be evaluated
     */
    var preloadDeviceOverrides = mapOf<Tier, Boolean>()

    /**
     * Has no effect. Defaults to `false`.
     *
     * On-device caching of paywall templates is configured per paywall in the Superwall
     * dashboard. This option is only reported in the `config_attributes` event and will be
     * removed in the next major version.
     */
    @Deprecated("Has no effect; on-device caching is configured per paywall in the dashboard.")
    var useCachedTemplates: Boolean = false

    /**
     * Automatically dismisses the paywall when a product is purchased or restored. Defaults to `true`.
     *
     * Set this to `false` to prevent the paywall from dismissing on purchase/restore.
     */
    var automaticallyDismiss: Boolean = true

    /** Defines the different types of views that can appear behind Apple's payment sheet during a transaction. */
    enum class TransactionBackgroundView {
        /** This shows your paywall background color overlayed with an activity indicator. */
        SPINNER,
    }

    /**
     * A map of product name to product identifier that allows you to override products on all paywalls.
     *
     * This is useful when you want to test different products on your paywalls or when you need to
     * override products dynamically. The keys in this map correspond to the product name placeholders
     * in your paywall (e.g., "primary", "secondary", "tertiary"), and the values are the product identifiers.
     *
     * Example:
     * ```
     * Superwall.instance.overrideProductsByName = mapOf(
     *     "primary" to "com.example.premium_monthly",
     *     "tertiary" to "com.example.premium_annual"
     * )
     * ```
     */
    var overrideProductsByName: Map<String, String> = emptyMap()

    /**
     * The view that appears behind Apple's payment sheet during a transaction. Defaults to `.spinner`.
     *
     * Set this to `null` to remove any background view during the transaction.
     *
     * **Note:** This feature is still in development and could change.
     */
    var transactionBackgroundView: TransactionBackgroundView? = TransactionBackgroundView.SPINNER

    /**
     * A color resource used to tint the spinner shown while a purchase or restoration
     * is in progress. Defaults to `null`, which keeps the system's default spinner color.
     *
     * Note: this only applies to the SDK's built-in loading view. If you present paywalls
     * yourself and pass a custom view via `PaywallBuilder.purchaseLoadingView`, style that
     * view directly instead.
     */
    @ColorRes
    var loadingColor: Int? = null

    /** Hide shimmer optimistically. */
    var optimisticLoading: Boolean = false

    /**
     * How long a presented paywall may take to finish loading before it's given up on.
     *
     * When the timeout passes the paywall is dismissed as declined with the
     * `webViewFailedToLoad` close reason, the same as a webview that can't load at all. Your
     * `PaywallPresentationHandler` gets `onDismiss`, followed by the feature block for a
     * non-gated placement or `onError` for a gated one, so you can show your own fallback.
     * A `paywallWebviewLoad_timeout` event is tracked. `null` (the default) means no timeout.
     */
    var timeoutAfter: Duration? = null

    /**
     * This will be invoked when `reroute_back_button` is enabled in Paywall settings.
     * You can use this to react to back button presses with custom logic.
     * Return `true` if the press has been consumed. Return `false` to fall back to SDK
     * logic: the press is forwarded into the paywall as a `back_button_input` message,
     * and the paywall either navigates its flow back one page or closes itself.
     */
    var onBackPressed: ((PaywallInfo?) -> Boolean)? = null

    /** Where a presented paywall is hosted. See [presentationHost]. */
    enum class PresentationHost {
        /** The paywall is shown in its own `SuperwallPaywallActivity`. */
        ACTIVITY,

        /**
         * **Experimental.** The paywall is shown as a view on top of the presenting Activity's
         * window, so that Activity is never covered (or stopped) by the paywall itself.
         * Meant for game engines, such as Unity, whose loop stops while another Activity covers
         * theirs. All presentation styles are supported. If the host Activity is destroyed while
         * the paywall is shown, including by a configuration change it doesn't handle itself, the
         * paywall is dismissed.
         */
        IN_WINDOW,
    }

    /**
     * **Experimental.** Chooses how presented paywalls are hosted. Defaults to
     * [PresentationHost.ACTIVITY]. This may change or be removed in a future release.
     */
    var presentationHost: PresentationHost = PresentationHost.ACTIVITY
}

internal fun SuperwallOptions.computedShouldPreload(tier: Tier) =
    paywalls.preloadDeviceOverrides[tier] ?: paywalls.shouldPreload

@Suppress("DEPRECATION")
internal fun PaywallOptions.toMap() =
    mapOf(
        "is_haptic_feedback_enabled" to isHapticFeedbackEnabled,
        "restore_failed" to
                mapOf(
                    "title" to restoreFailed.title,
                    "message" to restoreFailed.message,
                    "close_button_title" to restoreFailed.closeButtonTitle,
                ),
        "should_show_purchase_failure_alert" to shouldShowPurchaseFailureAlert,
        "should_preload" to shouldPreload,
        "preload_device_overrides" to preloadDeviceOverrides.mapKeys { it.key.raw },
        "use_cached_templates" to useCachedTemplates,
        "automatically_dismiss" to automaticallyDismiss,
        "transaction_background_view" to transactionBackgroundView?.name,
    )

/** The restore-failed alert text to show: the app's own text, or the localized default. */
internal data class RestoreFailedAlert(
    val title: String,
    val message: String,
    val closeButtonTitle: String,
)

internal fun PaywallOptions.RestoreFailed.localized(context: Context): RestoreFailedAlert {
    fun String.orLocalized(
        default: String,
        @StringRes resource: Int,
    ) = if (this == default) context.getString(resource) else this
    return RestoreFailedAlert(
        title = title.orLocalized(PaywallOptions.RestoreFailed.DEFAULT_TITLE, R.string.superwall_restore_failed_title),
        message = message.orLocalized(PaywallOptions.RestoreFailed.DEFAULT_MESSAGE, R.string.superwall_restore_failed_message),
        closeButtonTitle =
            closeButtonTitle.orLocalized(
                PaywallOptions.RestoreFailed.DEFAULT_CLOSE_BUTTON_TITLE,
                R.string.superwall_restore_failed_close,
            ),
    )
}
