package com.superwall.sdk.paywall.vc.Survey

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.superwall.sdk.config.models.Survey
import com.superwall.sdk.config.models.SurveyOption
import com.superwall.sdk.config.models.SurveyShowCondition
import com.superwall.sdk.dependencies.TriggerFactory
import com.superwall.sdk.models.paywall.PresentationCondition
import com.superwall.sdk.paywall.presentation.PaywallCloseReason
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.vc.PaywallViewController
import com.superwall.sdk.paywall.vc.delegate.PaywallLoadingState
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.SurveyAssignmentKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SurveyManager {
    private var otherAlertDialog: AlertDialog? = null

    fun presentSurveyIfAvailable(
        surveys: List<Survey>,
        paywallResult: PaywallResult,
        paywallCloseReason: PaywallCloseReason,
        presenter: PaywallViewController,
        loadingState: PaywallLoadingState,
        isDebuggerLaunched: Boolean,
        paywallInfo: PaywallInfo,
        storage: Storage,
        factory: TriggerFactory,
        completion: (SurveyPresentationResult) -> Unit
    ) {
        val survey = selectSurvey(surveys, paywallResult, paywallCloseReason) ?: run {
            completion(SurveyPresentationResult.NOSHOW)
            return
        }

        if (loadingState !is PaywallLoadingState.Ready && loadingState !is PaywallLoadingState.LoadingPurchase) {
            completion(SurveyPresentationResult.NOSHOW)
            return
        }

        if (survey.hasSeenSurvey(storage)) {
            completion(SurveyPresentationResult.NOSHOW)
            return
        }

        val isHoldout = survey.shouldAssignHoldout(isDebuggerLaunched)

        if (!isDebuggerLaunched) {
            // Make sure we don't assess this survey with this assignment key again.
            storage.save(survey.assignmentKey, SurveyAssignmentKey)
        }

        if (isHoldout) {
            Logger.debug(
                logLevel = LogLevel.info,
                scope = LogScope.paywallViewController,
                message = "The survey will not present."
            )
            completion(SurveyPresentationResult.HOLDOUT)
            return
        }

        // TODO: Use a bottom sheet dialog with a list rather than alertdialog
        val builder = AlertDialog.Builder(presenter.context)
        builder.setTitle(survey.title)
            .setMessage(survey.message)

        survey.options.shuffled().forEach { option ->
            builder.setPositiveButton(option.title) { _, _ ->
                selectedOption(
                    option,
                    survey,
                    null,
                    paywallInfo,
                    factory,
                    presenter,
                    isDebuggerLaunched,
                    completion
                )
            }
        }

        if (survey.includeOtherOption) {
            val otherOption = SurveyOption("000", "Other")
            builder.setPositiveButton("Other") { _, _ ->
                val editText = EditText(presenter.context)
                editText.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val text = s?.toString()?.trim()
                        if (text != null) {
                            otherAlertController?.actions?.get(0)?.isEnabled = text.isNotEmpty()
                        }

                        // Logic for enabling/disabling the submit button based on text
                    }
                })
                val otherBuilder = AlertDialog.Builder(presenter.context)
                otherBuilder.setView(editText)
                    .setPositiveButton("Submit") { _, _ ->
                        selectedOption(
                            otherOption, survey, editText.text.toString(), paywallInfo,
                            factory, presenter, isDebuggerLaunched, completion
                        )
                    }
                otherAlertDialog = otherBuilder.create()
                otherAlertDialog?.show()
            }
        }

        if (survey.includeCloseOption) {
            builder.setNegativeButton("Close") { _, _ ->
                // Tracking logic here
                completion(SurveyPresentationResult.Show)
            }
        }

        val alertDialog = builder.create()
        alertDialog.show()
    }

    private suspend fun selectedOption(
        option: SurveyOption,
        survey: Survey,
        customResponse: String?,
        paywallInfo: PaywallInfo,
        factory: TriggerFactory,
        presenter: PaywallViewController,
        isDebuggerLaunched: Boolean,
        completion: (SurveyPresentationResult) -> Unit
    ) {
        // Logic to handle option selection
    }

    private fun selectSurvey(
        surveys: List<Survey>,
        paywallResult: PaywallResult,
        paywallCloseReason: PaywallCloseReason
    ): Survey? {
        val isPurchased = paywallResult is PaywallResult.Purchased
        val isDeclined = paywallResult is PaywallResult.Declined
        val isManualClose = paywallCloseReason is PaywallCloseReason.ManualClose

        for (survey in surveys) {
            when (survey.presentationCondition) {
                SurveyShowCondition.ON_MANUAL_CLOSE -> if (isDeclined && isManualClose) return survey
                SurveyShowCondition.ON_PURCHASE -> if (isPurchased) return survey
            }
        }
        return null
    }
}
