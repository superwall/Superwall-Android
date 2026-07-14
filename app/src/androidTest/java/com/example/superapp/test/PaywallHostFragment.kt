package com.example.superapp.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.superwall.sdk.Superwall
import com.superwall.sdk.paywall.presentation.register

/**
 * Minimal test-host screen that presents a paywall from a [Fragment] via
 * [register] — mirroring the customer's fragment-hosted setup that surfaced the
 * regression fixed in PR #434 (stale transient presentation state carried onto a
 * re-presented cached paywall). The fragment is only the screen that *triggers*
 * presentation; the paywall itself shows full-screen in `SuperwallPaywallActivity`
 * via the `register()` path (NOT the embedded `getPaywall()` path).
 */
class PaywallHostFragment : Fragment() {
    // Parameterised so the whole flow keys off a single placement constant.
    var placement: String = RepresentTests.CONSUMABLE_REBUY_PLACEMENT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext())

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // On view-create, present the paywall exactly as the customer's fragment does.
        present()
    }

    /**
     * Re-invokes the same `register()` entry point. On the second call the SDK reuses
     * the CACHED `PaywallView`, which is precisely the path where the stale
     * `LoadingPurchase` overlay used to swallow the buy tap before PR #434.
     */
    fun present() {
        Superwall.instance.register(placement = placement)
    }
}
