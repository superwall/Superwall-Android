package com.superwall.sdk.misc.primitives

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDemandSequentialActorTest {
    private class Ctx(
        val log: MutableList<String>,
    )

    private fun action(block: suspend Ctx.() -> Unit) =
        object : TypedAction<Ctx> {
            override val execute: suspend Ctx.() -> Unit = block
        }

    @Test
    fun `an idle actor leaves no coroutine in its scope`() =
        runTest {
            Given("an OnDemandSequentialActor and a SequentialActor on their own scopes") {
                val onDemandScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val sequentialScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val onDemand = OnDemandSequentialActor<Ctx, Int>(0, onDemandScope)
                val sequential = SequentialActor<Ctx, Int>(0, sequentialScope)
                val ctx = Ctx(mutableListOf())

                When("each runs an action and goes idle") {
                    onDemand.effect(ctx, action { log += "on-demand" })
                    sequential.effect(ctx, action { log += "sequential" })
                    advanceUntilIdle()

                    Then("both ran their action") {
                        assertEquals(setOf("on-demand", "sequential"), ctx.log.toSet())
                    }
                    And("the on-demand actor leaves no child job, while SequentialActor keeps its loop") {
                        assertEquals(0, onDemandScope.coroutineContext.job.children.count())
                        assertEquals(1, sequentialScope.coroutineContext.job.children.count())
                    }
                }
                sequential.close()
            }
        }

    @Test
    fun `actions run one at a time in dispatch order`() =
        runTest {
            Given("an OnDemandSequentialActor") {
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val actor = OnDemandSequentialActor<Ctx, Int>(0, scope)
                val ctx = Ctx(mutableListOf())

                When("a slow action is followed by a fast one") {
                    actor.effect(
                        ctx,
                        action {
                            log += "slow start"
                            delay(100)
                            log += "slow end"
                        },
                    )
                    actor.effect(ctx, action { log += "fast" })
                    advanceUntilIdle()

                    Then("the fast one waits for the slow one to finish") {
                        assertEquals(listOf("slow start", "slow end", "fast"), ctx.log)
                    }
                    And("the actor drains again for work dispatched after it went idle") {
                        actor.effect(ctx, action { log += "later" })
                        advanceUntilIdle()
                        assertEquals("later", ctx.log.last())
                        assertEquals(0, scope.coroutineContext.job.children.count())
                    }
                }
            }
        }

    @Test
    fun `an action that calls back into the actor runs inline`() =
        runTest {
            Given("an OnDemandSequentialActor") {
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val actor = OnDemandSequentialActor<Ctx, Int>(0, scope)
                val ctx = Ctx(mutableListOf())

                When("an action awaits another action on the same actor") {
                    val done = CompletableDeferred<Unit>()
                    actor.effect(
                        ctx,
                        action {
                            actor.immediate(this, action { log += "inner" })
                            log += "outer"
                            done.complete(Unit)
                        },
                    )
                    advanceUntilIdle()

                    Then("it does not deadlock and the inner action runs first") {
                        assertTrue(done.isCompleted)
                        assertEquals(listOf("inner", "outer"), ctx.log)
                    }
                }
            }
        }

    @Test
    fun `immediate from outside waits for its turn and rethrows`() =
        runTest {
            Given("an OnDemandSequentialActor") {
                val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                val actor = OnDemandSequentialActor<Ctx, Int>(0, scope)
                val ctx = Ctx(mutableListOf())

                When("an outside caller awaits a throwing action") {
                    val result =
                        async {
                            runCatching { actor.immediate(ctx, action { error("boom") }) }
                        }
                    advanceUntilIdle()

                    Then("the caller gets the error and the actor keeps working") {
                        assertEquals("boom", result.await().exceptionOrNull()?.message)
                        actor.effect(ctx, action { log += "after" })
                        advanceUntilIdle()
                        assertEquals(listOf("after"), ctx.log)
                    }
                }
            }
        }
}
