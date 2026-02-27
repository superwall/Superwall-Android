package com.superwall.sdk.store.testmode.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.superwall.sdk.R
import kotlinx.coroutines.CompletableDeferred

sealed class PurchaseSimulationResult {
    data object Purchased : PurchaseSimulationResult()

    data object Abandoned : PurchaseSimulationResult()

    data object Failed : PurchaseSimulationResult()
}

internal class TestModePurchaseDrawer : BottomSheetDialogFragment() {
    private var result = CompletableDeferred<PurchaseSimulationResult>()

    companion object {
        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_PRICE = "price"
        private const val ARG_PERIOD = "period"
        private const val ARG_HAS_TRIAL = "has_trial"
        private const val ARG_TRIAL_TEXT = "trial_text"
        private const val ARG_ENTITLEMENTS = "entitlements"

        suspend fun show(
            activity: Activity,
            productIdentifier: String,
            localizedPrice: String,
            period: String,
            hasFreeTrial: Boolean,
            trialPeriodText: String,
            entitlementNames: List<String>,
        ): PurchaseSimulationResult {
            val fragmentActivity =
                activity as? FragmentActivity
                    ?: return PurchaseSimulationResult.Abandoned

            val drawer = TestModePurchaseDrawer()
            drawer.arguments =
                Bundle().apply {
                    putString(ARG_PRODUCT_ID, productIdentifier)
                    putString(ARG_PRICE, localizedPrice)
                    putString(ARG_PERIOD, period)
                    putBoolean(ARG_HAS_TRIAL, hasFreeTrial)
                    putString(ARG_TRIAL_TEXT, trialPeriodText)
                    putStringArrayList(ARG_ENTITLEMENTS, ArrayList(entitlementNames))
                }

            drawer.show(fragmentActivity.supportFragmentManager, "test_mode_purchase")
            return drawer.result.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = true
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.test_mode_sheet_bg)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.test_mode_purchase_drawer, container, false)

        val productId = arguments?.getString(ARG_PRODUCT_ID) ?: ""
        val price = arguments?.getString(ARG_PRICE) ?: ""
        val period = arguments?.getString(ARG_PERIOD) ?: ""
        val hasFreeTrial = arguments?.getBoolean(ARG_HAS_TRIAL) ?: false
        val trialText = arguments?.getString(ARG_TRIAL_TEXT) ?: ""
        val entitlements = arguments?.getStringArrayList(ARG_ENTITLEMENTS) ?: arrayListOf()

        // Populate product card
        view.findViewById<TextView>(R.id.product_id_text).text = productId

        if (hasFreeTrial && trialText.isNotEmpty()) {
            view.findViewById<TextView>(R.id.trial_text).apply {
                text = "$trialText free trial"
                visibility = View.VISIBLE
            }
        }

        view.findViewById<TextView>(R.id.price_text).text =
            if (period.isNotEmpty()) "$price / $period" else price

        if (entitlements.isNotEmpty()) {
            view.findViewById<TextView>(R.id.entitlements_text).apply {
                text = "Entitlements: ${entitlements.joinToString(", ")}"
                visibility = View.VISIBLE
            }
        }

        // Confirm button text depends on trial
        view.findViewById<TextView>(R.id.confirm_button).apply {
            text = if (hasFreeTrial) "Start Free Trial" else "Confirm Purchase"
            setOnClickListener {
                result.complete(PurchaseSimulationResult.Purchased)
                dismiss()
            }
        }

        // Close button
        view.findViewById<TextView>(R.id.close_button).setOnClickListener {
            result.complete(PurchaseSimulationResult.Abandoned)
            dismiss()
        }

        // Fail button
        view.findViewById<TextView>(R.id.fail_button).setOnClickListener {
            result.complete(PurchaseSimulationResult.Failed)
            dismiss()
        }

        return view
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!result.isCompleted) {
            result.complete(PurchaseSimulationResult.Abandoned)
        }
    }
}
