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
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CompletableDeferred

sealed class RestoreSimulationResult {
    data class Restored(
        val selectedEntitlements: Set<String>,
    ) : RestoreSimulationResult()

    data object Cancelled : RestoreSimulationResult()
}

class TestModeRestoreDrawer : BottomSheetDialogFragment() {
    private var result = CompletableDeferred<RestoreSimulationResult>()

    companion object {
        private const val ARG_ENTITLEMENTS = "entitlements"
        private const val ARG_CURRENT = "current"

        suspend fun show(
            activity: Activity,
            availableEntitlements: List<String>,
            currentEntitlements: Set<String>,
        ): RestoreSimulationResult {
            val fragmentActivity =
                activity as? FragmentActivity
                    ?: return RestoreSimulationResult.Cancelled

            val drawer = TestModeRestoreDrawer()
            drawer.arguments =
                Bundle().apply {
                    putStringArrayList(ARG_ENTITLEMENTS, ArrayList(availableEntitlements))
                    putStringArrayList(ARG_CURRENT, ArrayList(currentEntitlements))
                }

            drawer.show(fragmentActivity.supportFragmentManager, "test_mode_restore")
            return drawer.result.await()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val entitlements = arguments?.getStringArrayList(ARG_ENTITLEMENTS) ?: arrayListOf()
        val current = arguments?.getStringArrayList(ARG_CURRENT)?.toSet() ?: emptySet()

        val dp = { px: Int -> (px * resources.displayMetrics.density).toInt() }
        val selectedEntitlements = current.toMutableSet()

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
        header.addView(makeTestModeBadge(dp))
        header.addView(
            View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            },
        )
        header.addView(
            TextView(requireContext()).apply {
                text = "\u2715"
                textSize = 20f
                setOnClickListener {
                    result.complete(RestoreSimulationResult.Cancelled)
                    dismiss()
                }
            },
        )
        root.addView(header)

        // Title
        root.addView(
            TextView(requireContext()).apply {
                text = "Simulate Restore"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(16) }
            },
        )

        root.addView(
            TextView(requireContext()).apply {
                text = "Select the entitlements to restore for this test session."
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

        // Entitlement checkboxes
        for (entitlement in entitlements) {
            val checkbox =
                CheckBox(requireContext()).apply {
                    text = entitlement
                    textSize = 14f
                    isChecked = entitlement in current
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = dp(4) }
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedEntitlements.add(entitlement)
                        } else {
                            selectedEntitlements.remove(entitlement)
                        }
                    }
                }
            root.addView(checkbox)
        }

        if (entitlements.isEmpty()) {
            root.addView(
                TextView(requireContext()).apply {
                    text = "No entitlements available."
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
        }

        // Restore button
        root.addView(
            TextView(requireContext()).apply {
                text = "Restore"
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
                    result.complete(RestoreSimulationResult.Restored(selectedEntitlements.toSet()))
                    dismiss()
                }
            },
        )

        // Disclaimer
        root.addView(
            TextView(requireContext()).apply {
                text = "This is a simulated restore. No real transaction will occur."
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
            result.complete(RestoreSimulationResult.Cancelled)
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
