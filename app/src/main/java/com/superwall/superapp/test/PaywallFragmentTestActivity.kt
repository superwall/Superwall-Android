package com.superwall.superapp.test

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.superwall.sdk.paywall.presentation.get_paywall.builder.PaywallBuilder
import com.superwall.sdk.paywall.presentation.internal.state.PaywallResult
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.delegate.PaywallViewCallback
import kotlinx.coroutines.launch

/**
 * Manual, in-app analog of the `PaywallHostFragment` used by the `RepresentTests`
 * androidTest suite. Lets you reproduce the PR #434 re-present regression by hand:
 * embed a paywall in a [Fragment] via the public `getPaywall`/[PaywallBuilder] API,
 * buy, then tap "Re-present paywall" to re-attach the SAME cached [PaywallView] and
 * confirm the buy button is still alive.
 *
 * The androidTest [com.example.superapp.test.PaywallHostFragment] can't be launched by
 * the running app (it lives in the androidTest source set), so this mirrors it in main.
 */
class PaywallFragmentTestActivity : AppCompatActivity() {
    private val containerId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }

        val represent =
            Button(this).apply {
                text = "Re-present paywall"
                setOnClickListener {
                    (supportFragmentManager.findFragmentById(containerId) as? EmbeddedPaywallFragment)
                        ?.present()
                }
            }

        val container =
            FrameLayout(this).apply {
                id = containerId
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                    ).apply { weight = 1f }
            }

        root.addView(represent)
        root.addView(container)
        setContentView(root)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(containerId, EmbeddedPaywallFragment())
            }
        }
    }
}

/**
 * Embeds a paywall inside a [Fragment] via the public embed API
 * ([PaywallBuilder] -> `Superwall.getPaywall`), NOT via `register`. Mirrors the
 * customer's fragment-hosted setup from PR #434. Re-attaches the SAME cached
 * [PaywallView] on each [present] call.
 */
class EmbeddedPaywallFragment : Fragment() {
    // Same placement the RepresentTests suite uses.
    var placement: String = "non_recurring_product"

    private lateinit var container: FrameLayout
    private var paywallView: PaywallView? = null

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
        present()
    }

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
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(view)
                view.onViewCreated()
            }
    }
}
