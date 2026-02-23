package com.superwall.sdk.store.testmode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.superwall.sdk.R
import org.json.JSONObject

class TestModeAttributesViewer : DialogFragment() {
    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_ATTRIBUTES_JSON = "attributes_json"

        fun newInstance(
            title: String,
            attributesJson: String,
        ): TestModeAttributesViewer =
            TestModeAttributesViewer().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_TITLE, title)
                        putString(ARG_ATTRIBUTES_JSON, attributesJson)
                    }
            }
    }

    override fun getTheme(): Int = android.R.style.Theme_DeviceDefault_Light_NoActionBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.test_mode_attributes_viewer, container, false)

        val title = arguments?.getString(ARG_TITLE) ?: "Attributes"
        val json = arguments?.getString(ARG_ATTRIBUTES_JSON) ?: "{}"

        view.findViewById<TextView>(R.id.viewer_title).text = title
        view.findViewById<TextView>(R.id.back_button).setOnClickListener {
            dismiss()
        }

        val container = view.findViewById<LinearLayout>(R.id.attributes_container)
        val attributes = parseAttributes(json)

        for ((key, value) in attributes) {
            val row = inflater.inflate(R.layout.test_mode_attribute_row, container, false)
            row.findViewById<TextView>(R.id.attribute_key).text = key
            row.findViewById<TextView>(R.id.attribute_value).text = value
            row.setOnClickListener {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(key, value))
                Toast.makeText(requireContext(), "$key copied", Toast.LENGTH_SHORT).show()
            }
            container.addView(row)
        }

        if (attributes.isEmpty()) {
            val empty = TextView(requireContext())
            empty.text = "No attributes available."
            empty.textSize = 14f
            empty.setTextColor(0xFF999999.toInt())
            container.addView(empty)
        }

        return view
    }

    private fun parseAttributes(json: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result.add(key to obj.get(key).toString())
            }
        } catch (_: Exception) {
            // If parsing fails, return empty
        }
        return result.sortedBy { it.first }
    }
}
