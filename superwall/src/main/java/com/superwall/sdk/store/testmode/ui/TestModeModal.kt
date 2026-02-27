package com.superwall.sdk.store.testmode.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.superwall.sdk.R
import com.superwall.sdk.Superwall
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.serialization.AnyMapSerializer
import com.superwall.sdk.storage.TestModeSettings
import com.superwall.sdk.store.testmode.FreeTrialOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal data class TestModeModalResult(
    val entitlements: List<EntitlementSelection>,
    val freeTrialOverride: FreeTrialOverride,
)

internal class TestModeModal : BottomSheetDialogFragment() {
    private var result = CompletableDeferred<TestModeModalResult>()

    companion object {
        private const val ARG_REASON = "reason"
        private const val ARG_HAS_PURCHASE_CONTROLLER = "has_purchase_controller"
        private const val ARG_ENTITLEMENTS = "entitlements"
        private const val ARG_API_KEY = "api_key"
        private const val ARG_DASHBOARD_BASE_URL = "dashboard_base_url"
        private const val ARG_SAVED_SETTINGS = "saved_settings"

        suspend fun show(
            activity: Activity,
            reason: String,
            hasPurchaseController: Boolean,
            availableEntitlements: List<String>,
            apiKey: String = "",
            dashboardBaseUrl: String = "",
            savedSettings: TestModeSettings? = null,
        ): TestModeModalResult {
            return MainScope()
                .async {
                    val fragmentActivity =
                        activity as? FragmentActivity
                            ?: return@async TestModeModalResult(
                                emptyList(),
                                FreeTrialOverride.UseDefault,
                            )

                    val modal = TestModeModal()
                    modal.arguments =
                        Bundle().apply {
                            putString(ARG_REASON, reason)
                            putBoolean(ARG_HAS_PURCHASE_CONTROLLER, hasPurchaseController)
                            putStringArrayList(ARG_ENTITLEMENTS, ArrayList(availableEntitlements))
                            putString(ARG_API_KEY, apiKey)
                            putString(ARG_DASHBOARD_BASE_URL, dashboardBaseUrl)
                            savedSettings?.let {
                                putString(
                                    ARG_SAVED_SETTINGS,
                                    Json.encodeToString(TestModeSettings.serializer(), it),
                                )
                            }
                        }
                    modal.isCancelable = false

                    modal.show(fragmentActivity.supportFragmentManager, "test_mode_modal")
                    return@async modal.result.await()
                }.await()
        }
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
        val view = inflater.inflate(R.layout.test_mode_modal, container, false)

        val reason = arguments?.getString(ARG_REASON) ?: ""
        val userId = Superwall.instance.userId
        val hasPurchaseController = arguments?.getBoolean(ARG_HAS_PURCHASE_CONTROLLER) ?: false
        val entitlements = arguments?.getStringArrayList(ARG_ENTITLEMENTS) ?: arrayListOf()
        val apiKey = arguments?.getString(ARG_API_KEY) ?: ""
        val dashboardBaseUrl = arguments?.getString(ARG_DASHBOARD_BASE_URL) ?: ""
        val userAttributes = Json.encodeToString(AnyMapSerializer, Superwall.instance.userAttributes)

        val savedSettingsJson = arguments?.getString(ARG_SAVED_SETTINGS)

        val savedSettings =
            savedSettingsJson?.let {
                try {
                    Json.decodeFromString(TestModeSettings.serializer(), it)
                } catch (_: Exception) {
                    null
                }
            }

        val savedSelectionsByName =
            savedSettings?.entitlementSelections?.associateBy { it.identifier } ?: emptyMap()

        var selectedOverride = savedSettings?.freeTrialOverride ?: FreeTrialOverride.UseDefault

        // Populate static fields
        view.findViewById<TextView>(R.id.reason_text).text = reason
        view.findViewById<TextView>(R.id.user_id_value).text = userId ?: "(anonymous)"
        view.findViewById<TextView>(R.id.purchase_controller_value).text =
            if (hasPurchaseController) "Provided" else "Not provided"

        // Copy User ID button
        view.findViewById<TextView>(R.id.copy_user_id_button).setOnClickListener {
            copyToClipboard("User ID", userId ?: "")
        }

        // View on Dashboard button
        view.findViewById<TextView>(R.id.view_dashboard_button).setOnClickListener {
            if (dashboardBaseUrl.isNotEmpty() && apiKey.isNotEmpty() && !userId.isNullOrEmpty()) {
                val url = "$dashboardBaseUrl/sdk-link/applications/$apiKey/users/$userId"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    Toast
                        .makeText(requireContext(), "Could not open browser", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        IOScope().launch {
            val deviceAttributes = Json.encodeToString(AnyMapSerializer, Superwall.instance.deviceAttributes())
            MainScope().launch {
                view.findViewById<View>(R.id.device_attributes_row).setOnClickListener {
                    val viewer = TestModeAttributesViewer.newInstance("Device Attributes", deviceAttributes)
                    viewer.show(parentFragmentManager, "device_attributes")
                }
            }
        }
        // Device Attributes row

        // User Attributes row
        view.findViewById<View>(R.id.user_attributes_row).setOnClickListener {
            val viewer = TestModeAttributesViewer.newInstance("User Attributes", userAttributes)
            viewer.show(parentFragmentManager, "user_attributes")
        }

        // Free trial override spinner
        val freeTrialSpinner = view.findViewById<Spinner>(R.id.free_trial_spinner)
        val overrideOptions = FreeTrialOverride.entries.toList()
        val overrideAdapter =
            ArrayAdapter(
                requireContext(),
                R.layout.test_mode_spinner_item,
                overrideOptions.map { it.displayName },
            )
        overrideAdapter.setDropDownViewResource(R.layout.test_mode_spinner_dropdown_item)
        freeTrialSpinner.adapter = overrideAdapter
        freeTrialSpinner.setSelection(
            overrideOptions.indexOf(selectedOverride).coerceAtLeast(0),
        )
        freeTrialSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    itemView: View?,
                    position: Int,
                    id: Long,
                ) {
                    selectedOverride = overrideOptions[position]
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // Entitlements section
        val rowHolders = mutableListOf<EntitlementRowViewHolder>()
        if (entitlements.isNotEmpty()) {
            val section = view.findViewById<LinearLayout>(R.id.entitlements_section)
            section.visibility = View.VISIBLE
            val entitlementsContainer = view.findViewById<LinearLayout>(R.id.entitlements_container)

            for (entitlement in entitlements) {
                val initialSelection =
                    savedSelectionsByName[entitlement]
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
        }

        // Continue button
        view.findViewById<TextView>(R.id.continue_button).setOnClickListener {
            val selections = rowHolders.map { it.currentSelection }
            result.complete(
                TestModeModalResult(
                    entitlements = selections,
                    freeTrialOverride = selectedOverride,
                ),
            )
            dismiss()
        }

        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        retainInstance = true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!result.isCompleted) {
            result.complete(
                TestModeModalResult(
                    entitlements = emptyList(),
                    freeTrialOverride = FreeTrialOverride.UseDefault,
                ),
            )
        }
    }

    private fun copyToClipboard(
        label: String,
        text: String,
    ) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "$label copied", Toast.LENGTH_SHORT).show()
    }
}
