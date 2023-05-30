package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.models.triggers.TriggerResult

// File.kt

suspend fun Superwall.confirmHoldoutAssignment(
    input: AssignmentPipelineOutput,
    dependencyContainer: DependencyContainer? = null
) {
    val dependencyContainer = dependencyContainer ?: this.dependencyContainer
    if (input.triggerResult is TriggerResult.Holdout) {
        input.confirmableAssignment?.let { confirmableAssignment ->
            dependencyContainer.configManager.confirmAssignment(confirmableAssignment)
        }
    }
}
