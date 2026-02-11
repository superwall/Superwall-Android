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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.superwall.sdk.store.testmode.FreeTrialOverride
import kotlinx.coroutines.CompletableDeferred

data class TestModeModalResult(
    val entitlements: Set<String>,
    val freeTrialOverride: FreeTrialOverride,
)

class TestModeModal : BottomSheetDialogFragment() {
    private var result = CompletableDeferred<TestModeModalResult>()

    companion object {
        private const val ARG_REASON = "reason"
        private const val ARG_USER_ID = "user_id"
        private const val ARG_ALIAS_ID = "alias_id"
        private const val ARG_IS_IDENTIFIED = "is_identified"
        private const val ARG_HAS_PURCHASE_CONTROLLER = "has_purchase_controller"
        private const val ARG_ENTITLEMENTS = "entitlements"

        suspend fun show(
            activity: Activity,
            reason: String,
            userId: String?,
            aliasId: String?,
            isIdentified: Boolean,
            hasPurchaseController: Boolean,
            availableEntitlements: List<String>,
        ): TestModeModalResult {
            val fragmentActivity =
                activity as? FragmentActivity
                    ?: return TestModeModalResult(emptySet(), FreeTrialOverride.UseDefault)

            val modal = TestModeModal()
            modal.arguments =
                Bundle().apply {
                    putString(ARG_REASON, reason)
                    putString(ARG_USER_ID, userId)
                    putString(ARG_ALIAS_ID, aliasId)
                    putBoolean(ARG_IS_IDENTIFIED, isIdentified)
                    putBoolean(ARG_HAS_PURCHASE_CONTROLLER, hasPurchaseController)
                    putStringArrayList(ARG_ENTITLEMENTS, ArrayList(availableEntitlements))
                }
            modal.isCancelable = false

            modal.show(fragmentActivity.supportFragmentManager, "test_mode_modal")
            return modal.result.await()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val reason = arguments?.getString(ARG_REASON) ?: ""
        val userId = arguments?.getString(ARG_USER_ID)
        val aliasId = arguments?.getString(ARG_ALIAS_ID)
        val isIdentified = arguments?.getBoolean(ARG_IS_IDENTIFIED) ?: false
        val hasPurchaseController = arguments?.getBoolean(ARG_HAS_PURCHASE_CONTROLLER) ?: false
        val entitlements = arguments?.getStringArrayList(ARG_ENTITLEMENTS) ?: arrayListOf()

        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }
        val selectedEntitlements = mutableSetOf<String>()
        var selectedOverride = FreeTrialOverride.UseDefault

        val scroll = ScrollView(requireContext())
        val root =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(32))
            }

        // Header
        root.addView(makeTestModeBadge(dp))

        root.addView(
            TextView(requireContext()).apply {
                text = "Test Mode Active"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(12) }
            },
        )

        // Reason
        root.addView(
            TextView(requireContext()).apply {
                text = reason
                textSize = 14f
                setTextColor(Color.GRAY)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(8) }
            },
        )

        // User info section
        root.addView(makeSectionHeader("User Info", dp))
        root.addView(
            makeInfoRow(
                "User ID",
                userId ?: "(anonymous)",
                dp,
            ),
        )
        root.addView(makeInfoRow("Alias ID", aliasId ?: "n/a", dp))
        root.addView(makeInfoRow("Identified", if (isIdentified) "Yes" else "No", dp))
        root.addView(makeInfoRow("Has Purchase Controller", if (hasPurchaseController) "Yes" else "No", dp))

        // Entitlements section
        if (entitlements.isNotEmpty()) {
            root.addView(makeSectionHeader("Entitlements", dp))
            root.addView(
                TextView(requireContext()).apply {
                    text = "Select entitlements to activate for this test session."
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(4) }
                },
            )

            for (entitlement in entitlements) {
                root.addView(
                    CheckBox(requireContext()).apply {
                        text = entitlement
                        textSize = 14f
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { topMargin = dp(2) }
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedEntitlements.add(entitlement) else selectedEntitlements.remove(entitlement)
                        }
                    },
                )
            }
        }

        // Free trial override
        root.addView(makeSectionHeader("Free Trial Override", dp))
        val radioGroup =
            RadioGroup(requireContext()).apply {
                orientation = RadioGroup.VERTICAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(4) }
            }
        FreeTrialOverride.entries.forEach { override ->
            radioGroup.addView(
                RadioButton(requireContext()).apply {
                    text = override.displayName
                    textSize = 14f
                    isChecked = override == FreeTrialOverride.UseDefault
                    setOnClickListener { selectedOverride = override }
                },
            )
        }
        root.addView(radioGroup)

        // Continue button
        root.addView(
            TextView(requireContext()).apply {
                text = "Continue"
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
                    result.complete(
                        TestModeModalResult(
                            entitlements = selectedEntitlements.toSet(),
                            freeTrialOverride = selectedOverride,
                        ),
                    )
                    dismiss()
                }
            },
        )

        // Dashboard link hint
        root.addView(
            TextView(requireContext()).apply {
                text = "Configure test mode users in your Superwall Dashboard."
                textSize = 11f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(12) }
            },
        )

        scroll.addView(root)
        return scroll
    }

    private fun makeSectionHeader(
        title: String,
        dp: (Int) -> Int,
    ): TextView =
        TextView(requireContext()).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(16) }
        }

    private fun makeInfoRow(
        label: String,
        value: String,
        dp: (Int) -> Int,
    ): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) }

            addView(
                TextView(requireContext()).apply {
                    text = label
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(
                TextView(requireContext()).apply {
                    text = value
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.END
                },
            )
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
