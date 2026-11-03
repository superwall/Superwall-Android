package com.superwall.sdk.paywall.view

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.CustomPlacement
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.ReviewRequested
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DeviceHelperFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.MainScope
import com.superwall.sdk.misc.launchWithTracking
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.internal.dismiss
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.delegate.PaywallViewEventCallback
import com.superwall.sdk.paywall.view.webview.messaging.PaywallWebEvent
import com.superwall.sdk.review.ReviewManager
import com.superwall.sdk.storage.ReviewCount
import com.superwall.sdk.storage.ReviewData
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.core_data.convertFromJsonElement
import com.superwall.sdk.store.transactions.TransactionManager
import com.superwall.sdk.store.transactions.TransactionManager.PurchaseSource.Internal
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles the events a paywall's web view sends: close, purchase, restore, links, custom actions,
 * custom placements, review requests and notifications.
 *
 * The container hands this to every [PaywallView] it creates as its event callback.
 * [com.superwall.sdk.Superwall.eventDidOccur] forwards here too, for views made elsewhere.
 */
internal class PaywallEventHandler(
    private val mainScope: MainScope,
    private val ioScope: IOScope,
    private val transactionManager: TransactionManager,
    private val delegate: SuperwallDelegateAdapter,
    private val track: suspend (Trackable) -> Unit,
    private val reviewManager: ReviewManager,
    private val deviceHelper: DeviceHelper,
    private val storage: Storage,
    private val notificationFactory: DeviceHelperFactory,
    private val recheckPreload: suspend () -> Unit,
    /** The current activity, used for the in-app review and notifications. */
    private val currentActivity: () -> Activity?,
    /** The activity the app supplied, tried first for the Play Store fallback. */
    private val providedActivity: () -> Activity?,
    private val dismissPaywall: suspend (PaywallView, PaywallResult, PaywallCloseReason) -> Unit = { view, result, reason ->
        dismiss(view, result = result, closeReason = reason)
    },
    private val openUrl: (Activity, String) -> Unit = { activity, url ->
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    },
    private val scheduleNotifications: suspend (PaywallView, PaywallWebEvent.ScheduleNotification, Activity?, DeviceHelperFactory) -> Boolean =
        { view, event, activity, factory ->
            val notifications = PaywallNotifications.forPaywall(view, activity)
            // Cancel any existing fallback notification of the same type before scheduling
            // the dynamic notification from the paywall
            notifications?.attemptToSchedule(
                notifications = listOf(event.localNotification),
                factory = factory,
                cancelExisting = true,
                applySandboxScaling = true,
            ) != null
        },
) : PaywallViewEventCallback {
    /** The purchase in progress. A second purchase is ignored while it runs. */
    internal var purchaseTask: Job? = null
        private set

    override suspend fun eventDidOccur(
        paywallEvent: PaywallWebEvent,
        paywallView: PaywallView,
    ) {
        mainScope.launchWithTracking {
            Logger.debug(
                logLevel = LogLevel.debug,
                scope = LogScope.paywallView,
                message = "Event Did Occur",
                info = mapOf("event" to paywallEvent),
            )

            when (paywallEvent) {
                is PaywallWebEvent.Closed -> {
                    dismissPaywall(
                        paywallView,
                        PaywallResult.Declined(),
                        PaywallCloseReason.ManualClose,
                    )
                }

                is PaywallWebEvent.InitiatePurchase -> {
                    if (purchaseTask != null) {
                        // If a purchase is already in progress, do not start another
                        return@launchWithTracking
                    }
                    paywallView.updateState(
                        PaywallViewState.Updates.SetLoadingState(PaywallLoadingState.LoadingPurchase),
                    )
                    purchaseTask =
                        launch {
                            try {
                                transactionManager.purchase(
                                    Internal(
                                        paywallEvent.productId,
                                        paywallView.controller.state,
                                        replacementMode = paywallEvent.replacementMode,
                                    ),
                                    shouldDismiss = paywallEvent.shouldDismiss,
                                )
                            } finally {
                                // Ensure the task is cleared once the purchase is complete or if an error occurs
                                purchaseTask = null
                            }
                        }
                }

                is PaywallWebEvent.InitiateRestore -> {
                    transactionManager.tryToRestorePurchases(paywallView)
                }

                is PaywallWebEvent.OpenedURL -> {
                    delegate.paywallWillOpenURL(url = paywallEvent.url)
                }

                is PaywallWebEvent.OpenedUrlInChrome -> {
                    delegate.paywallWillOpenURL(url = paywallEvent.url)
                }

                is PaywallWebEvent.OpenedDeepLink -> {
                    delegate.paywallWillOpenDeepLink(url = paywallEvent.url)
                }

                is PaywallWebEvent.Custom -> {
                    delegate.handleCustomPaywallAction(name = paywallEvent.string)
                }

                is PaywallWebEvent.CustomPlacement -> {
                    @Suppress("UNCHECKED_CAST")
                    track(
                        CustomPlacement(
                            placementName = paywallEvent.name,
                            params =
                                paywallEvent.params.convertFromJsonElement() as? Map<String, Any>
                                    ?: emptyMap(),
                            paywallInfo = paywallView.info,
                        ),
                    )
                }

                is PaywallWebEvent.RequestReview -> {
                    ioScope.launch { requestReview(paywallEvent, paywallView) }
                }

                is PaywallWebEvent.ScheduleNotification -> {
                    val scheduled =
                        scheduleNotifications(
                            paywallView,
                            paywallEvent,
                            currentActivity(),
                            notificationFactory,
                        )
                    if (!scheduled) {
                        Logger.debug(
                            LogLevel.error,
                            LogScope.paywallView,
                            message = "No superwall paywall host alive to schedule notifications",
                        )
                    }
                }

                is PaywallWebEvent.RequestPermission -> {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallView,
                        message = "Permission requested: ${paywallEvent.permissionType.rawValue}",
                    )
                }

                is PaywallWebEvent.RequestCallback -> {
                    Logger.debug(
                        LogLevel.debug,
                        LogScope.paywallView,
                        message = "Custom callback requested: ${paywallEvent.name}",
                    )
                }
            }
        }
    }

    private suspend fun requestReview(
        paywallEvent: PaywallWebEvent.RequestReview,
        paywallView: PaywallView,
    ) {
        try {
            when (paywallEvent.type) {
                PaywallWebEvent.RequestReview.Type.INAPP -> {
                    val reviewInfo = reviewManager.requestReviewFlow()
                    val activity = currentActivity() ?: return
                    reviewManager.launchReviewFlow(activity, reviewInfo)
                    // Track successful review request
                    val currentCount = deviceHelper.reviewRequestsTotal()
                    storage.write(ReviewData, ReviewCount(currentCount + 1))
                    track(
                        ReviewRequested(
                            count = currentCount + 1,
                            type = paywallEvent.type.rawValue,
                        ),
                    )
                    recheckPreload()
                }

                else -> {
                    val packageName = deviceHelper.urlScheme
                    val url = "https://play.google.com/store/apps/details?id=$packageName"
                    val activity =
                        providedActivity() ?: paywallView.encapsulatingActivity?.get()
                    activity?.let { openUrl(it, url) }
                }
            }
        } catch (e: Exception) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.paywallView,
                message = "Failed to request review",
                error = e,
            )
        }
    }
}
