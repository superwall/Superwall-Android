package com.superwall.sdk.misc.primitives

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SequentialActorTest {
    private fun action(block: suspend () -> Unit) =
        object : TypedAction<Unit> {
            override val execute: suspend Unit.() -> Unit = { block() }
        }

    @Test
    fun `throwing effect action does not escape the scope or kill the consumer loop`() =
        runTest {
            Given("a SequentialActor running on a scope without a handler-bearing SuperwallScope") {
                var uncaught: Throwable? = null
                val handler = CoroutineExceptionHandler { _, e -> uncaught = e }
                val scope =
                    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
                val actor = SequentialActor<Unit, Int>(0, scope)

                When("an effect action throws and another action follows") {
                    var laterActionRan = false
                    actor.effect(Unit, action { error("boom") })
                    actor.effect(Unit, action { laterActionRan = true })
                    advanceUntilIdle()

                    Then("the exception does not escape to the scope") {
                        assertNull(uncaught)
                    }
                    And("the consumer loop is still alive and runs subsequent actions") {
                        assertTrue(laterActionRan)
                    }
                }
                actor.close()
            }
        }

    @Test
    fun `throwing immediate action still rethrows to the caller`() =
        runTest {
            Given("a SequentialActor") {
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val actor = SequentialActor<Unit, Int>(0, scope)

                When("an immediate action throws") {
                    var thrown: Throwable? = null
                    val caller =
                        launch {
                            try {
                                actor.immediate(Unit, action { error("boom") })
                            } catch (e: IllegalStateException) {
                                thrown = e
                            }
                        }
                    advanceUntilIdle()
                    caller.join()

                    Then("the caller receives the exception") {
                        assertEquals("boom", thrown?.message)
                    }
                    And("the consumer loop still runs subsequent actions") {
                        var laterActionRan = false
                        actor.effect(Unit, action { laterActionRan = true })
                        advanceUntilIdle()
                        assertTrue(laterActionRan)
                    }
                }
                actor.close()
            }
        }
}
