package com.superwall.sdk.config

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.assignment.ConfirmedAssignment
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.Trigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AssignmentServiceTest {
    private val storedVariant = Experiment.Variant("v1", Experiment.Variant.VariantType.TREATMENT, "paywall")
    private val newVariant = Experiment.Variant("v2", Experiment.Variant.VariantType.HOLDOUT, null)
    private val tracked = mutableListOf<Trackable>()
    private val confirmed = mutableListOf<String>()

    // ConfirmedAssignment has no equals, so compare its fields.
    private fun List<ConfirmedAssignment>.pairs() = map { it.experimentId to it.variant }

    private fun service(
        triggers: Set<Trigger>?,
        confirm: (String) -> Either<ConfirmedAssignment?, Throwable>,
    ) = AssignmentService(
        track = { tracked += it },
        triggers = { triggers },
        storedAssignments = { mapOf("exp1" to storedVariant) },
        confirm = { eventData ->
            confirmed += eventData.name
            confirm(eventData.name)
        },
    )

    @Test
    fun `getAssignments returns the stored assignments`() =
        Given("one stored assignment") {
            val result = service(triggers = null) { Either.Success(null) }.getAssignments()
            Then("it comes back as a confirmed assignment") {
                assertEquals(listOf("exp1" to storedVariant), result.getOrThrow().pairs())
            }
        }

    @Test
    fun `without a config only ConfirmAllAssignments is tracked`() =
        runTest {
            val result = service(triggers = null) { Either.Success(null) }.confirmAllAssignments()
            Given("no config yet") {
                Then("the result is empty and nothing is confirmed") {
                    assertEquals(emptyList<ConfirmedAssignment>(), result.getOrThrow())
                    assertEquals(emptyList<String>(), confirmed)
                }
                And("ConfirmAllAssignments is tracked") {
                    assertEquals(listOf<Trackable>(InternalSuperwallEvent.ConfirmAllAssignments), tracked)
                }
            }
        }

    @Test
    fun `every trigger is confirmed and failures are skipped`() =
        runTest {
            val triggers =
                setOf(
                    Trigger(eventName = "campaign_a", rules = emptyList()),
                    Trigger(eventName = "campaign_b", rules = emptyList()),
                    Trigger(eventName = "campaign_c", rules = emptyList()),
                )
            val result =
                service(triggers) { name ->
                    when (name) {
                        "campaign_a" -> Either.Success(ConfirmedAssignment("exp2", newVariant))
                        "campaign_b" -> Either.Success(null)
                        else -> Either.Failure(IllegalStateException("rules failed"))
                    }
                }.confirmAllAssignments()
            Given("three triggers: one new assignment, one without, one failing") {
                Then("each trigger is confirmed") {
                    assertEquals(listOf("campaign_a", "campaign_b", "campaign_c"), confirmed)
                }
                And("the result holds the stored and the new assignment") {
                    assertEquals(
                        setOf("exp1" to storedVariant, "exp2" to newVariant),
                        result.getOrThrow().pairs().toSet(),
                    )
                }
            }
        }
}
