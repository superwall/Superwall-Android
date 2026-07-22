package com.example.superapp.test

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.superwall.sdk.paywall.presentation.get_paywall.builder.PaywallBuilder
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallViewCallback
import kotlinx.coroutines.launch

/**
 * Minimal test-host screen that EMBEDS a paywall inside a [Fragment] via the public
 * embed API ([PaywallBuilder] -> [com.superwall.sdk.Superwall.getPaywall]), NOT via
 * [com.superwall.sdk.paywall.presentation.register].
 */
class PaywallHostFragment : Fragment() {
    // Parameterised so the whole flow keys off a single placement constant.
    var placement: String = RepresentTests.CONSUMABLE_REBUY_PLACEMENT

    private lateinit var container: FrameLayout
    private var paywallView: PaywallView? = null

    // Minimal embed delegate. The fragment host stays on screen so the test can
    // re-embed the same paywall, so we intentionally do NOT finish the activity here.
    private val delegate =
        object : PaywallViewCallback {
            override fun onFinished(
                paywall: PaywallView,
                result: PaywallResult,
                shouldDismiss: Boolean,
            ) {
                // no-op: keep the host alive for re-embedding.
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).also { this.container = it }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // On view-create, embed the paywall exactly as the customer's fragment does.
        present()
    }

    /**
     * Obtains the embedded paywall via the public [PaywallBuilder]/`getPaywall` API and
     * attaches it into the fragment's container. On the second call the SDK reuses the
     * CACHED `PaywallView` (via `PaywallManager.getPaywallView`'s cache-hit branch),
     * which is precisely the path whose stale `LoadingPurchase` overlay used to swallow
     * the buy tap before PR #434.
     */
    fun present() {
        viewLifecycleOwner.lifecycleScope.launch {
            embedPaywall()
        }
    }

    private suspend fun embedPaywall() {
        // Detach any previously-embedded instance before re-embedding the (cached) view.
        paywallView?.let { (it.parent as? ViewGroup)?.removeView(it) }

        PaywallBuilder(placement)
            .delegate(delegate)
            .activity(requireActivity())
            .build()
            .onSuccess { view ->
                paywallView = view
                // `build()` already ran getPaywall() (cache-hit reset) + beforeViewCreated();
                // ensure the view isn't still parented, attach it, then drive onViewCreated()
                // as PaywallComposable does.
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(view)
                view.onViewCreated()
            }
    }
}
