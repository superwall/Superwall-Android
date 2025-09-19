package com.superwall.sdk.paywall.view

import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.paywall.PaywallPresentationStyle
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallCacheLogic
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.view.delegate.PaywallLoadingState
import com.superwall.sdk.paywall.view.survey.SurveyPresentationResult
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Date

data class PaywallViewState(
    val paywall: Paywall,
    val deviceHelper: DeviceHelper,
    val request: PresentationRequest? = null,
    val presentationStyle: PaywallPresentationStyle = paywall.presentation.style,
    val paywallStatePublisher: MutableSharedFlow<PaywallState>? = null,
    val paywallResult: PaywallResult? = null,
    // / Stores the completion block when calling dismiss.
    val dismissCompletionBlock: (() -> Unit)? = null,
    val callbackInvoked: Boolean = false,
    val viewCreatedCompletion: ((Boolean) -> Unit)? = null,
    // / Defines when Browser is presenting in app.
    val isBrowserViewPresented: Boolean = false,
    val interceptTouchEvents: Boolean = false,
    // / Whether the survey was shown, not shown, or in a holdout. Defaults to not shown.
    val surveyPresentationResult: SurveyPresentationResult = SurveyPresentationResult.NOSHOW,
    val loadingState: PaywallLoadingState = PaywallLoadingState.Unknown,
    // / Defines whether the view is being presented or not.
    val isPresented: Boolean = false,
    val presentationWillPrepare: Boolean = true,
    val presentationDidFinishPrepare: Boolean = false,
    // / `true` if there's a survey to complete and the paywall is displayed in a modal style.
    val didDisableSwipeForSurvey: Boolean = false,
    // / If the user match a rule with an occurrence, this needs to be saved on paywall presentation.
    val unsavedOccurrence: TriggerRuleOccurrence? = null,
) {
    val info: PaywallInfo
        get() = paywall.getInfo(request?.presentationInfo?.eventData)

    // / Determines whether the paywall is presented or not.
    val isActive: Boolean
        get() = isPresented

    internal val cacheKey: String = PaywallCacheLogic.key(paywall.identifier, deviceHelper.locale)

    override fun toString(): String =
        """PaywallViewState(
            |  paywallId: ${paywall.identifier}
            |  presentationStyle: $presentationStyle
            |  loadingState: $loadingState
            |  isPresented: $isPresented
            |  isActive: $isActive
            |  presentationWillPrepare: $presentationWillPrepare
            |  presentationDidFinishPrepare: $presentationDidFinishPrepare
            |  callbackInvoked: $callbackInvoked
            |  isBrowserViewPresented: $isBrowserViewPresented
            |  interceptTouchEvents: $interceptTouchEvents
            |  surveyPresentationResult: $surveyPresentationResult
            |  paywallResult: $paywallResult
            |  hasRequest: ${request != null}
            |  hasStatePublisher: ${paywallStatePublisher != null}
            |  hasUnsavedOccurrence: ${unsavedOccurrence != null}
            |  hasViewCreatedCompletion: ${viewCreatedCompletion != null}
            |  hasDismissCompletionBlock: ${dismissCompletionBlock != null}
            |)
        """.trimMargin()

    sealed class Updates(
        val transform: (PaywallViewState) -> PaywallViewState,
    ) {
        class SetRequest(
            val req: PresentationRequest,
            val publisher: MutableSharedFlow<PaywallState>?,
            val occurrence: TriggerRuleOccurrence?,
        ) : Updates({ state ->
                state.copy(
                    request = req,
                    paywallStatePublisher = publisher,
                    unsavedOccurrence = occurrence,
                )
            })

        class SetPresentationConfig(
            val styleOverride: PaywallPresentationStyle?,
            val completion: ((Boolean) -> Unit)?,
        ) : Updates({ state ->
                state.copy(
                    presentationStyle =
                        if (styleOverride != null && styleOverride !is PaywallPresentationStyle.None) {
                            styleOverride
                        } else {
                            state.paywall.presentation.style
                        },
                    viewCreatedCompletion = completion,
                )
            })

        object PresentationWillBegin : Updates({ state ->
            state.paywall.closeReason = PaywallCloseReason.None
            state.copy(callbackInvoked = false)
        })

        object ShimmerStarted : Updates({ state ->
            state.paywall.shimmerLoadingInfo.startAt = Date()
            state.copy(presentationWillPrepare = false)
        })

        object ResetPresentationPreparations : Updates({ state ->
            state.copy(
                presentationWillPrepare = true,
                presentationDidFinishPrepare = false,
            )
        })

        class InitiateDismiss(
            val result: PaywallResult,
            val closeReason: PaywallCloseReason,
            val completion: (() -> Unit)?,
        ) : Updates({ state ->
                state.paywall.closeReason = closeReason
                state.copy(
                    dismissCompletionBlock = completion,
                    paywallResult = result,
                )
            })

        object CallbackInvoked : Updates({ state ->
            state.copy(callbackInvoked = true)
        })

        class UpdateSurveyState(
            val result: SurveyPresentationResult,
        ) : Updates({ state ->
                state.copy(surveyPresentationResult = result)
            })

        object CleanupAfterDestroy : Updates({ state ->
            state.dismissCompletionBlock?.invoke()
            state.copy(
                paywallResult = null,
                isPresented = false,
                dismissCompletionBlock = null,
            )
        })

        object ClearViewCreatedCompletion : Updates({ state ->
            state.copy(viewCreatedCompletion = null)
        })

        object ClearUnsavedOccurrence : Updates({ state ->
            state.copy(unsavedOccurrence = null)
        })

        object SetPresentedAndFinished : Updates({ state ->
            state.copy(
                isPresented = true,
                presentationDidFinishPrepare = true,
            )
        })

        object ShimmerEnded : Updates({ state ->
            state.paywall.shimmerLoadingInfo.endAt = Date()
            state
        })

        object WebLoadingStarted : Updates({ state ->
            if (state.paywall.webviewLoadingInfo.startAt == null) {
                state.paywall.webviewLoadingInfo.startAt = Date()
            }
            state
        })

        object WebLoadingFailed : Updates({ state ->
            if (state.paywall.webviewLoadingInfo.failAt == null) {
                state.paywall.webviewLoadingInfo.failAt = Date()
            }
            state
        })

        class SetInterceptTouchEvents(
            val intercept: Boolean,
        ) : Updates({ state ->
                state.copy(interceptTouchEvents = intercept)
            })

        class SetBrowserPresented(
            val presented: Boolean,
        ) : Updates({ state ->
                state.copy(isBrowserViewPresented = presented)
            })

        class SetLoadingState(
            val newState: PaywallLoadingState,
        ) : Updates({ state ->
                state.copy(loadingState = newState)
            })

        object ClearStatePublisher : Updates({
            it.copy(paywallStatePublisher = null)
        })

        /**
         * Hides or displays the paywall spinner.
         *
         * @param isHidden A Boolean indicating whether to show or hide the spinner.
         */

        data class ToggleSpinner(
            val hidden: Boolean,
        ) : Updates({ state ->
                when {
                    hidden -> {
                        if (state.loadingState is PaywallLoadingState.ManualLoading ||
                            state.loadingState is PaywallLoadingState.LoadingPurchase
                        ) {
                            state.copy(loadingState = PaywallLoadingState.Ready)
                        } else {
                            state
                        }
                    }

                    else -> {
                        if (state.loadingState is PaywallLoadingState.Ready) {
                            state.copy(
                                loadingState =
                                    PaywallLoadingState.ManualLoading,
                            )
                        } else {
                            state
                        }
                    }
                }
            })
    }
}
