package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest

/**
 * Confirms the paywall assignment, if it exists.
 *
 * This is split from the holdout assignment because overrides can make the
 * paywall present even if the user is subscribed. We only know the overrides
 * at this point.
 */
suspend fun Superwall.confirmPaywallAssignment(
    request: PresentationRequest,
    input: PresentablePipelineOutput,
    dependencyContainer: DependencyContainer? = null
) {
    val dependencyContainer = dependencyContainer ?: this.dependencyContainer
    // Debuggers shouldn't confirm assignments.
    if (request.flags.isDebuggerLaunched) {
        return
    }

    input.confirmableAssignment?.let { confirmableAssignment ->
        dependencyContainer.configManager.confirmAssignment(confirmableAssignment)
    }
}
