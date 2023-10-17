package com.superwall.sdk.paywall.vc.Survey

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.superwall.sdk.R
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.TrackingLogic
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
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
        activity: Activity?,
        paywallViewController: PaywallViewController,
        loadingState: PaywallLoadingState,
        isDebuggerLaunched: Boolean,
        paywallInfo: PaywallInfo,
        storage: Storage,
        factory: TriggerFactory,
        completion: (SurveyPresentationResult) -> Unit
    ) {
        val activity = activity.let { it } ?: run {
            completion(SurveyPresentationResult.NOSHOW)
            return
        }

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

        val dialog = BottomSheetDialog(activity)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        val surveyView = LayoutInflater.from(activity).inflate(R.layout.survey_bottom_sheet, null)
        dialog.setContentView(surveyView)

        val optionsToShow = mutableListOf<String>()
        optionsToShow.addAll(survey.options.map { it.title })

        // Include 'Other' and 'Close' options in the ListView data source if necessary
        if (survey.includeOtherOption) {
            optionsToShow.add("Other")
        }
        if (survey.includeCloseOption) {
            optionsToShow.add("Close")
        }

        val titleTextView = surveyView.findViewById<TextView>(R.id.title)
        val messageTextView = surveyView.findViewById<TextView>(R.id.message)

        titleTextView.text = survey.title
        messageTextView.text = survey.message

        val listView: ListView = surveyView.findViewById(R.id.surveyListView)
        val surveyOptionsAdapter = ArrayAdapter(
            activity,
            R.layout.list_item,
            optionsToShow
        )

        listView.adapter = surveyOptionsAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < survey.options.size) {
                // Standard option selected
                dialog.setOnDismissListener {
                    handleDialogDismissal(
                        isDebuggerLaunched = isDebuggerLaunched,
                        survey = survey,
                        option = survey.options[position],
                        customResponse = null,
                        paywallInfo = paywallInfo,
                        factory = factory,
                        paywallViewController = paywallViewController,
                        completion = completion
                    )
                }
                dialog.dismiss()
            } else {
                // Special case for 'Other' or 'Close'
                val selectedItem = optionsToShow[position]
                if (selectedItem == "Other") {
                    // Create AlertDialog with an EditText
                    val editText = EditText(activity)
                    val customAlertView = LayoutInflater.from(activity).inflate(R.layout.custom_alert_dialog_layout, null)

                    val otherBuilder = AlertDialog.Builder(activity)
                    otherBuilder.setCancelable(false)
                    otherBuilder.setView(customAlertView)

                    val option = SurveyOption("000", "Other")

                    otherBuilder.setPositiveButton("Submit") { _, _ ->
                        // Intentionally left blank
                    }

                    val otherDialog = otherBuilder.create()

                    val customDialogTitle = customAlertView.findViewById<TextView>(R.id.customDialogTitle)
                    val customDialogMessage = customAlertView.findViewById<TextView>(R.id.customDialogMessage)
                    val customEditText = customAlertView.findViewById<EditText>(R.id.editText)

                    customDialogTitle.text = survey.title
                    customDialogMessage.text = survey.message

                    customEditText.addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            val text = s?.toString()?.trim()
                            otherDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !text.isNullOrEmpty()
                        }

                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })

                    // Disable the 'Submit' button initially
                    otherDialog.setOnShowListener {
                        otherDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    }

                    dialog.setOnDismissListener {
                        otherDialog.show()
                        otherDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                        // Auto-select the EditText
                        customEditText.requestFocus()
                    }

                    otherDialog.setOnShowListener {
                        // Set OnClickListener here
                        otherDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            handleDialogDismissal(
                                isDebuggerLaunched = isDebuggerLaunched,
                                survey = survey,
                                option = option,
                                customResponse = editText.text.toString(),
                                paywallInfo = paywallInfo,
                                factory = factory,
                                paywallViewController = paywallViewController,
                                completion = completion
                            )
                            otherDialog.dismiss()
                        }
                    }

                    // Dismiss the Survey
                    dialog.dismiss()
                } else if (selectedItem == "Close") {
                    CoroutineScope(Dispatchers.IO).launch {
                        val event = InternalSuperwallEvent.SurveyClose()
                        Superwall.instance.track(event)
                    }
                    dialog.dismiss()
                    completion(SurveyPresentationResult.SHOW)
                }
            }
        }

        dialog.show()
    }

    private fun handleDialogDismissal(
        isDebuggerLaunched: Boolean,
        survey: Survey,
        option: SurveyOption,
        customResponse: String?,
        paywallInfo: PaywallInfo,
        factory: TriggerFactory,
        paywallViewController: PaywallViewController,
        completion: (SurveyPresentationResult) -> Unit
    ) {
        if (isDebuggerLaunched) {
            completion(SurveyPresentationResult.SHOW)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val event = InternalSuperwallEvent.SurveyResponse(
                survey,
                option,
                customResponse,
                paywallInfo
            )

            val outcome = TrackingLogic.canTriggerPaywall(
                event,
                factory.makeTriggers(),
                paywallViewController
            )

            Superwall.instance.track(event)

            if (outcome == TrackingLogic.ImplicitTriggerOutcome.DontTriggerPaywall) {
                completion(SurveyPresentationResult.SHOW)
            }
        }
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
