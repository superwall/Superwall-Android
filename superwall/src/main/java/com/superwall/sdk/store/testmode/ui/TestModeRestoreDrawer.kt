package com.superwall.sdk.store.testmode.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.superwall.sdk.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json

internal sealed class RestoreSimulationResult {
    data class Restored(
        val selectedEntitlements: List<EntitlementSelection>,
    ) : RestoreSimulationResult()

    data object Cancelled : RestoreSimulationResult()
}

internal class TestModeRestoreDrawer : BottomSheetDialogFragment() {
    private var result = CompletableDeferred<RestoreSimulationResult>()

    companion object {
        private const val ARG_ENTITLEMENTS = "entitlements"
        private const val ARG_CURRENT_SELECTIONS = "current_selections"

        suspend fun show(
            activity: Activity,
            availableEntitlements: List<String>,
            currentSelections: List<EntitlementSelection>,
        ): RestoreSimulationResult {
            val fragmentActivity =
                activity as? FragmentActivity
                    ?: return RestoreSimulationResult.Cancelled

            val drawer = TestModeRestoreDrawer()
            drawer.arguments =
                Bundle().apply {
                    putStringArrayList(ARG_ENTITLEMENTS, ArrayList(availableEntitlements))
                    putString(
                        ARG_CURRENT_SELECTIONS,
                        Json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(EntitlementSelection.serializer()),
                            currentSelections,
                        ),
                    )
                }

            drawer.show(fragmentActivity.supportFragmentManager, "test_mode_restore")
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
        val view = inflater.inflate(R.layout.test_mode_restore_drawer, container, false)

        val entitlements = arguments?.getStringArrayList(ARG_ENTITLEMENTS) ?: arrayListOf()
        val currentSelectionsJson = arguments?.getString(ARG_CURRENT_SELECTIONS)
        val currentSelections =
            currentSelectionsJson?.let {
                try {
                    Json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(EntitlementSelection.serializer()),
                        it,
                    )
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()

        val currentByName = currentSelections.associateBy { it.identifier }

        // Close button
        view.findViewById<TextView>(R.id.close_button).setOnClickListener {
            result.complete(RestoreSimulationResult.Cancelled)
            dismiss()
        }

        // Dynamic entitlement rows
        val entitlementsContainer = view.findViewById<LinearLayout>(R.id.entitlements_container)
        val rowHolders = mutableListOf<EntitlementRowViewHolder>()
        if (entitlements.isNotEmpty()) {
            for (entitlement in entitlements) {
                val initialSelection =
                    currentByName[entitlement]
                        ?: EntitlementSelection(identifier = entitlement)
                val holder =
                    EntitlementRowViewHolder(
                        context = requireContext(),
                        entitlementName = entitlement,
                        initialSelection = initialSelection,
                        onSelectionChanged = {},
                    )
                rowHolders.add(holder)
                entitlementsContainer.addView(holder.view)
            }
        } else {
            view.findViewById<TextView>(R.id.no_entitlements_text).visibility = View.VISIBLE
        }

        // Restore button
        view.findViewById<TextView>(R.id.restore_button).setOnClickListener {
            val selections = rowHolders.map { it.currentSelection }
            result.complete(RestoreSimulationResult.Restored(selections))
            dismiss()
        }

        return view
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!result.isCompleted) {
            result.complete(RestoreSimulationResult.Cancelled)
        }
    }
}
