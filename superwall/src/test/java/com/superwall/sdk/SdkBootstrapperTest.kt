package com.superwall.sdk

import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SdkBootstrapperTest {
    private val scope = IOScope(Dispatchers.Unconfined)
    private val steps = CopyOnWriteArrayList<String>()
    private var results = CopyOnWriteArrayList<Result<Unit>>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun bootstrapper(
        cachedStatus: SubscriptionStatus? = null,
        shouldMatch: Boolean = true,
        fetchConfiguration: suspend () -> Unit = { steps += "config" },
        configureIdentity: suspend () -> Unit = { steps += "identity" },
    ) = SdkBootstrapper(
        scope = scope,
        readCachedSubscriptionStatus = { cachedStatus },
        restoreCachedCustomerInfo = { steps += "restoreCustomerInfo" },
        setSubscriptionStatus = { steps += "status:${it::class.simpleName}" },
        mergeCustomerInfo = { steps += "mergeCustomerInfo" },
        startListeners = { steps += "listeners" },
        observeAdConsent = { steps += "adConsent" },
        shouldMatchInstall = { shouldMatch },
        markInstallMatchPending = { steps += "markMatchPending" },
        recordAppInstall = { steps += "recordInstall" },
        fetchConfiguration = fetchConfiguration,
        configureIdentity = configureIdentity,
        matchInstall = { steps += "matchInstall" },
        mainDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `startup runs in order and completes`() =
        Given("an install that may be matched") {
            When("the SDK starts") {
                bootstrapper(cachedStatus = SubscriptionStatus.Inactive).start { results += it }
                Then("cached state and listeners come before the background work") {
                    assertEquals(
                        listOf(
                            "restoreCustomerInfo",
                            "status:Inactive",
                            "mergeCustomerInfo",
                            "listeners",
                            "adConsent",
                            "markMatchPending",
                            "recordInstall",
                            "config",
                            "identity",
                            "matchInstall",
                        ),
                        steps.toList(),
                    )
                }
                And("completion gets success") {
                    assertEquals(listOf(Result.success(Unit)), results.toList())
                }
            }
        }

    @Test
    fun `no cached status starts as Unknown, and no match when not eligible`() =
        Given("no cached status and an install that may not be matched") {
            When("the SDK starts") {
                bootstrapper(cachedStatus = null, shouldMatch = false).start(null)
                Then("the status is Unknown and nothing is matched") {
                    assertTrue("status:Unknown" in steps)
                    assertTrue("markMatchPending" !in steps)
                    assertTrue("matchInstall" !in steps)
                }
            }
        }

    @Test
    fun `config and identity run in parallel`() =
        Given("config that waits for identity to start") {
            val identityStarted = CompletableDeferred<Unit>()
            When("the SDK starts") {
                bootstrapper(
                    fetchConfiguration = {
                        identityStarted.await()
                        steps += "config"
                    },
                    configureIdentity = {
                        identityStarted.complete(Unit)
                        steps += "identity"
                    },
                ).start { results += it }
                Then("both finish and completion succeeds") {
                    assertTrue(steps.containsAll(listOf("config", "identity", "matchInstall")))
                    assertEquals(listOf(Result.success(Unit)), results.toList())
                }
            }
        }

    @Test
    fun `a failed config fetch completes with the failure`() =
        Given("config that throws") {
            val error = IllegalStateException("no config")
            When("the SDK starts") {
                bootstrapper(fetchConfiguration = { throw error }).start { results += it }
                Then("completion gets the failure and the install is not matched") {
                    assertEquals(1, results.size)
                    // Coroutines may hand back a copy of the exception, so compare type and message.
                    val failure = results.single().exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    assertEquals(error.message, failure?.message)
                    assertTrue("matchInstall" !in steps)
                }
            }
        }
}
