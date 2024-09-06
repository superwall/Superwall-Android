import com.superwall.sdk.config.ConfigLogic
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.Network
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class Assignments(
    private val storage: Storage,
    private val network: Network,
    private val ioScope: CoroutineScope,
    unconfirmedAssignments: Map<ExperimentID, Experiment.Variant> = emptyMap(),
) {
    // A memory store of assignments that are yet to be confirmed.
    private var _unconfirmedAssignments = unconfirmedAssignments.toMutableMap()
    val unconfirmedAssignments: Map<ExperimentID, Experiment.Variant>
        get() = _unconfirmedAssignments

    fun choosePaywallVariants(triggers: Set<Trigger>) {
        updateAssignments { confirmedAssignments ->
            ConfigLogic.chooseAssignments(
                fromTriggers = triggers,
                confirmedAssignments = confirmedAssignments,
            )
        }
    }

    suspend fun getAssignments(triggers: Set<Trigger>) {
        val assignments = network.getAssignments()

        updateAssignments { confirmedAssignments ->
            ConfigLogic.transferAssignmentsFromServerToDisk(
                assignments = assignments,
                triggers = triggers,
                confirmedAssignments = confirmedAssignments,
                unconfirmedAssignments = unconfirmedAssignments,
            )
        }
    }

    fun confirmAssignment(assignment: ConfirmableAssignment) {
        val postback: AssignmentPostback = AssignmentPostback.create(assignment)
        ioScope.launch { network.confirmAssignments(postback) }

        updateAssignments { confirmedAssignments ->
            ConfigLogic.move(
                assignment,
                unconfirmedAssignments,
                confirmedAssignments,
            )
        }
    }

    private fun updateAssignments(operation: (Map<ExperimentID, Experiment.Variant>) -> ConfigLogic.AssignmentOutcome) {
        var confirmedAssignments = storage.getConfirmedAssignments()

        val updatedAssignments = operation(confirmedAssignments)
        _unconfirmedAssignments = updatedAssignments.unconfirmed.toMutableMap()
        confirmedAssignments = updatedAssignments.confirmed.toMutableMap()

        storage.saveConfirmedAssignments(confirmedAssignments)
    }

    fun reset() {
        _unconfirmedAssignments.clear()
    }
}
