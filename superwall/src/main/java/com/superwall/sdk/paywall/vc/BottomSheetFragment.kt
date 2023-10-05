package com.superwall.sdk.paywall.vc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BottomSheetFragment(
    private val paywallView: View
) : BottomSheetDialogFragment() {


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return paywallView // This would return the PaywallViewController view to be shown as BottomSheet.
    }

    companion object {
        fun newInstance(paywallView: View): BottomSheetFragment {
            return BottomSheetFragment(paywallView)
        }
    }
}