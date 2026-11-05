package com.superwall.sdk.config

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.assignment.ConfirmedAssignment
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.utilities.withErrorTracking
import java.util.Date

/**
 * Reads the experiment assignments stored on device and confirms the assignment of every
 * trigger in the config.
 *
 * @param triggers the triggers of the current config, or `null` while there is no config.
 * @param confirm confirms the assignment of one placement, as a register call would.
 */
internal class AssignmentService(
    private val track: suspend (Trackable) -> Unit,
    private val triggers: () -> Set<Trigger>?,
    private val storedAssignments: () -> Map<ExperimentID, Experiment.Variant>,
    private val confirm: suspend (EventData) -> Either<ConfirmedAssignment?, Throwable>,
) {
    /** The assignments confirmed so far, from storage. */
    fun getAssignments(): Result<List<ConfirmedAssignment>> =
        withErrorTracking {
            storedAssignments().map { ConfirmedAssignment(it.key, it.value) }
        }.toResult()

    /**
     * Tracks `ConfirmAllAssignments`, then confirms the assignment of every trigger and
     * returns the stored assignments with the newly confirmed ones.
     */
    suspend fun confirmAllAssignments(): Result<List<ConfirmedAssignment>> =
        withErrorTracking {
            track(InternalSuperwallEvent.ConfirmAllAssignments)
            val triggers = triggers() ?: return@withErrorTracking emptyList()
            val assignments =
                storedAssignments()
                    .map { ConfirmedAssignment(it.key, it.value) }
                    .toMutableSet()

            triggers.forEach {
                val eventData = EventData(name = it.eventName, parameters = emptyMap(), createdAt = Date())
                confirm(eventData).fold(
                    onSuccess = { confirmed -> confirmed?.let { assignment -> assignments.add(assignment) } },
                ) { error ->
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.superwallCore,
                        message = "Failed to confirm assignment",
                        error = error,
                    )
                }
            }
            assignments.toList()
        }.toResult()
}
