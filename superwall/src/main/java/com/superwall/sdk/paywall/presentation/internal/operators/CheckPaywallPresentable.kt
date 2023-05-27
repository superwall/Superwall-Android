package com.superwall.sdk.paywall.presentation.internal.operators

import android.app.Activity
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.paywall.vc.PaywallViewController

data class PresentablePipelineOutput(
    val debugInfo: Map<String, Any>,
    val paywallViewController: PaywallViewController,
    val presenter: Activity,
    val confirmableAssignment: ConfirmableAssignment?
)
