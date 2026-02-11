package com.superwall.sdk.store.testmode.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CompletableDeferred

sealed class PurchaseSimulationResult {
    data object Purchased : PurchaseSimulationResult()

    data object Abandoned : PurchaseSimulationResult()

    data object Failed : PurchaseSimulationResult()
}

class TestModePurchaseDrawer : BottomSheetDialogFragment() {
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val productId = arguments?.getString(ARG_PRODUCT_ID) ?: ""
        val price = arguments?.getString(ARG_PRICE) ?: ""
        val period = arguments?.getString(ARG_PERIOD) ?: ""
        val hasFreeTrial = arguments?.getBoolean(ARG_HAS_TRIAL) ?: false
        val trialText = arguments?.getString(ARG_TRIAL_TEXT) ?: ""
        val entitlements = arguments?.getStringArrayList(ARG_ENTITLEMENTS) ?: arrayListOf()

        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }

        val scroll = ScrollView(requireContext())
        val root =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(32))
            }

        // Header
        val header =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val badge = makeTestModeBadge(dp)
        header.addView(badge)
        header.addView(
            View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            },
        )
        val closeButton =
            TextView(requireContext()).apply {
                text = "\u2715"
                textSize = 20f
                setOnClickListener {
                    result.complete(PurchaseSimulationResult.Abandoned)
                    dismiss()
                }
            }
        header.addView(closeButton)
        root.addView(header)

        // Product card
        val card =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#F5F5F5"))
                        cornerRadius = dp(12).toFloat()
                    }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(16) }
            }

        card.addView(
            TextView(requireContext()).apply {
                text = productId
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            },
        )

        if (hasFreeTrial && trialText.isNotEmpty()) {
            card.addView(
                TextView(requireContext()).apply {
                    text = "$trialText free trial"
                    textSize = 14f
                    setTextColor(Color.parseColor("#4CAF50"))
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(4) }
                },
            )
        }

        card.addView(
            TextView(requireContext()).apply {
                text = if (period.isNotEmpty()) "$price / $period" else price
                textSize = 14f
                setTextColor(Color.GRAY)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
            },
        )

        if (entitlements.isNotEmpty()) {
            card.addView(
                TextView(requireContext()).apply {
                    text = "Entitlements: ${entitlements.joinToString(", ")}"
                    textSize = 12f
                    setTextColor(Color.DKGRAY)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(8) }
                },
            )
        }
        root.addView(card)

        // Confirm button
        val buttonText = if (hasFreeTrial) "Start Free Trial" else "Confirm Purchase"
        val confirmButton =
            TextView(requireContext()).apply {
                text = buttonText
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#2196F3"))
                        cornerRadius = dp(12).toFloat()
                    }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(20) }
                setOnClickListener {
                    result.complete(PurchaseSimulationResult.Purchased)
                    dismiss()
                }
            }
        root.addView(confirmButton)

        // Simulate failure button
        val failButton =
            TextView(requireContext()).apply {
                text = "Simulate Failure"
                textSize = 14f
                setTextColor(Color.parseColor("#F44336"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
                setOnClickListener {
                    result.complete(PurchaseSimulationResult.Failed)
                    dismiss()
                }
            }
        root.addView(failButton)

        // Disclaimer
        root.addView(
            TextView(requireContext()).apply {
                text = "This is a simulated purchase. No real transaction will occur."
                textSize = 11f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(16) }
            },
        )

        scroll.addView(root)
        return scroll
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!result.isCompleted) {
            result.complete(PurchaseSimulationResult.Abandoned)
        }
    }

    private fun makeTestModeBadge(dp: (Int) -> Int): TextView =
        TextView(requireContext()).apply {
            text = "TEST MODE"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background =
                GradientDrawable().apply {
                    setColor(Color.parseColor("#FF9800"))
                    cornerRadius = dp(4).toFloat()
                }
        }
}
