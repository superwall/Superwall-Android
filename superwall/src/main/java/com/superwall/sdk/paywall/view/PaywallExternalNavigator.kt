package com.superwall.sdk.paywall.view

import android.content.ActivityNotFoundException
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.primitives.BaseContext
import com.superwall.sdk.misc.primitives.Reducer
import com.superwall.sdk.misc.primitives.StateActor
import com.superwall.sdk.misc.primitives.TypedAction
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.net.MalformedURLException
import java.net.URI

/**
 * Where one [PaywallView] sent the user outside the paywall: the web checkout sheet it opened, if
 * any. Owned by [PaywallExternalNavigator].
 */
internal data class ExternalNavigationState(
    val paymentSheet: PaymentSheet = PaymentSheet.Closed,
) {
    /** The web checkout Custom Tab opened from the paywall. */
    sealed class PaymentSheet {
        object Closed : PaymentSheet()

        data class Open(
            val url: String,
        ) : PaymentSheet()
    }

    // -----------------------------------------------------------------------
    // Pure state mutations — (ExternalNavigationState) -> ExternalNavigationState
    // -----------------------------------------------------------------------

    internal sealed class Updates(
        override val reduce: (ExternalNavigationState) -> ExternalNavigationState,
    ) : Reducer<ExternalNavigationState> {
        data class SheetOpened(
            val url: String,
        ) : Updates({ it.copy(paymentSheet = PaymentSheet.Open(url)) })

        object SheetClosed : Updates({ it.copy(paymentSheet = PaymentSheet.Closed) })
    }

    // -----------------------------------------------------------------------
    // Actions — leave the paywall via ExternalNavigatorContext
    // -----------------------------------------------------------------------

    internal sealed class Actions(
        override val execute: suspend ExternalNavigatorContext.() -> Unit,
    ) : TypedAction<ExternalNavigatorContext> {
        /**
         * Opens web checkout in the partial-height Custom Tab. The sheet counts as open from the
         * start, and as closed again only if there is nothing to launch it with.
         */
        data class OpenPaymentSheet(
            val url: String,
        ) : Actions({
                update(Updates.SheetOpened(url))
                try {
                    log(LogLevel.debug, "Opening checkout URL in Chrome Custom Tab: $url")
                    when (launchCheckout(url)) {
                        CheckoutLaunch.Launched -> Unit
                        CheckoutLaunch.NoLauncher -> {
                            log(
                                LogLevel.error,
                                "Activity result launcher not initialized - cannot present Chrome Custom Tab",
                            )
                            update(Updates.SheetClosed)
                        }
                    }
                } catch (e: MalformedURLException) {
                    log(LogLevel.debug, "Invalid URL provided for \"Open In-App URL\" click behavior.", e)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    log(LogLevel.debug, "Exception thrown for \"Open In-App URL\" click behavior.", e)
                }
            })

        /** The web checkout Custom Tab closed with [statusCode]. */
        data class CheckoutClosed(
            val statusCode: Int,
        ) : Actions({
                log(LogLevel.debug, "Chrome Custom Tab closed with status code: $statusCode")
                update(Updates.SheetClosed)
                updatePaywall(PaywallViewState.Updates.SetBrowserPresented(false))
            })

        /** Opens [url] in a Custom Tab, then marks the browser as presented. */
        data class OpenInAppBrowser(
            val url: String,
        ) : Actions({
                try {
                    openCustomTab(URI(url).toString())
                    updatePaywall(PaywallViewState.Updates.SetBrowserPresented(true))
                } catch (e: MalformedURLException) {
                    log(LogLevel.debug, "Invalid URL provided for \"Open In-App URL\" click behavior.")
                } catch (e: Throwable) {
                    log(LogLevel.debug, "Exception thrown for \"Open In-App URL\" click behavior.")
                }
            })

        /** Marks the browser as presented, then opens [url] in the user's browser. */
        data class OpenExternalBrowser(
            val url: String,
        ) : Actions({
                try {
                    val parsed = URI(url).toString()
                    updatePaywall(PaywallViewState.Updates.SetBrowserPresented(true))
                    openExternalBrowser(parsed)
                } catch (e: MalformedURLException) {
                    log(LogLevel.debug, "Invalid URL provided for \"Open External URL\" click behavior.")
                } catch (e: Throwable) {
                    log(LogLevel.debug, "Exception thrown for \"Open External URL\" click behavior.")
                }
            })

        /** Reports the deep link as a paywall event, then hands it to the app. */
        data class OpenDeepLink(
            val url: String,
        ) : Actions({
                deepLinkOpened(url)
                try {
                    startDeepLink(url)
                } catch (e: ActivityNotFoundException) {
                    log(LogLevel.warn, "No activity found to handle deep link: $url")
                }
            })
    }
}

/** What happened when web checkout was handed to the host. */
internal sealed class CheckoutLaunch {
    object Launched : CheckoutLaunch()

    /** No in-window host and no registered launcher: nothing can open the sheet. */
    object NoLauncher : CheckoutLaunch()
}

/** Context for [ExternalNavigationState.Actions]: the ways a paywall can send the user out. */
internal interface ExternalNavigatorContext : BaseContext<ExternalNavigationState, ExternalNavigatorContext> {
    /** Hands web checkout for [url] to the in-window host or the activity's launcher. */
    fun launchCheckout(url: String): CheckoutLaunch

    /** Opens [url] in a Custom Tab in a new task. */
    fun openCustomTab(url: String)

    /** Opens [url] with the user's default handler, in a new task. */
    fun openExternalBrowser(url: String)

    /** Starts the activity that handles the deep link [url]. */
    fun startDeepLink(url: String)

    /** Reports that the paywall opened the deep link [url]. */
    fun deepLinkOpened(url: String)

    /** Applies an update to the paywall's state. */
    fun updatePaywall(update: PaywallViewState.Updates)

    fun log(
        level: LogLevel,
        message: String,
        error: Throwable? = null,
    ) = Logger.debug(logLevel = level, scope = LogScope.paywallView, message = message, error = error)
}

/**
 * Sends the user out of one [PaywallView]: web checkout, in-app and external browsers and deep
 * links. Runs each action at once on the caller's thread, as the view did, since callers read
 * [isPaymentSheetOpen] right after opening checkout and the dismissal checks it synchronously.
 */
internal class PaywallExternalNavigator(
    override val scope: CoroutineScope,
    override val storage: Storage,
    override val tracker: suspend (TrackableSuperwallEvent) -> Unit,
    private val checkoutLauncher: (String) -> CheckoutLaunch,
    private val customTab: (String) -> Unit,
    private val externalBrowser: (String) -> Unit,
    private val deepLink: (String) -> Unit,
    private val onDeepLinkOpened: (String) -> Unit,
    private val applyPaywallUpdate: (PaywallViewState.Updates) -> Unit,
    override val actor: StateActor<ExternalNavigatorContext, ExternalNavigationState> =
        StateActor(ExternalNavigationState(), scope),
) : ExternalNavigatorContext {
    val isPaymentSheetOpen: Boolean
        get() = state.value.paymentSheet is ExternalNavigationState.PaymentSheet.Open

    override fun launchCheckout(url: String) = checkoutLauncher(url)

    override fun openCustomTab(url: String) = customTab(url)

    override fun openExternalBrowser(url: String) = externalBrowser(url)

    override fun startDeepLink(url: String) = deepLink(url)

    override fun deepLinkOpened(url: String) = onDeepLinkOpened(url)

    override fun updatePaywall(update: PaywallViewState.Updates) = applyPaywallUpdate(update)

    /** Runs [action] through the actor now, on the calling thread; none of them suspend. */
    fun dispatch(action: ExternalNavigationState.Actions) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { actor.immediate(this@PaywallExternalNavigator, action) }
    }

    /** The checkout sheet won't produce a result, so it counts as closed. */
    fun sheetClosed() = update(ExternalNavigationState.Updates.SheetClosed)
}
