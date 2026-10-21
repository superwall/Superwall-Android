package com.superwall.sdk.config

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.PaywallViewState
import com.superwall.sdk.paywall.view.webview.WebviewChecker
import com.superwall.sdk.storage.LocalStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaywallPreloadTest {
    private val testDispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        runCatching { unmockkObject(ConfigLogic) }
        runCatching { unmockkStatic("com.superwall.sdk.paywall.view.webview.SWWebViewKt") }
    }

    @Test
    fun `preloadAllPaywalls only schedules one task when already in progress`() =
        runTest(testDispatcher) {
            Given("preloading already running") {
                val factory = mockk<PaywallPreload.Factory>(relaxed = true)
                val storage = mockk<LocalStorage>(relaxed = true)
                val assignments = mockk<Assignments>(relaxed = true)
                val paywallManager = mockk<PaywallManager>(relaxed = true)

                mockkObject(ConfigLogic)
                every { ConfigLogic.filterTriggers(any(), any()) } returns emptySet()
                coEvery {
                    ConfigLogic.getAllActiveTreatmentPaywallIds(any(), any(), any(), any())
                } returns emptySet()

                mockkObject(WebviewChecker)
                every { WebviewChecker.webviewExists } returns true

                val preload =
                    PaywallPreload(
                        factory = factory,
                        scope = IOScope(testDispatcher),
                        storage = storage,
                        assignments = assignments,
                        paywallManager = paywallManager,
                        track = {},
                    )

                val config = Config.stub()
                val context = mockk<android.content.Context>()

                When("preloadAllPaywalls is invoked twice before tasks complete") {
                    // First call schedules the job
                    preload.preloadAllPaywalls(config, context)
                    // Second call should short-circuit because currentPreloadingTask is non-null
                    preload.preloadAllPaywalls(config, context)

                    Then("the factory is invoked only once after execution") {
                        testDispatcher.scheduler.advanceUntilIdle()
                        coVerify(exactly = 1) { factory.provideRuleEvaluator(context) }
                    }
                }
            }
        }

    @Test
    fun `removeUnusedPaywallVCsFromCache clears removed and changed paywalls but not presented`() =
        runTest(testDispatcher) {
            Given("old and new configs with removed and changed paywalls") {
                val factory = mockk<PaywallPreload.Factory>(relaxed = true)
                val storage = mockk<LocalStorage>(relaxed = true)
                val assignments = mockk<Assignments>(relaxed = true)
                val paywallManager = mockk<PaywallManager>(relaxed = true)

                val presentedPaywall = Paywall.stub().copy(identifier = "presented", cacheKey = "presented-ck")
                val presentedView =
                    mockk<PaywallView> {
                        every { state } returns PaywallViewState(presentedPaywall, locale = "en")
                    }
                every { paywallManager.currentView } returns presentedView

                val oldConfig =
                    Config.stub().copy(
                        paywalls =
                            listOf(
                                Paywall.stub().copy(identifier = "keep", cacheKey = "keep-ck"),
                                Paywall.stub().copy(identifier = "remove", cacheKey = "remove-ck"),
                                Paywall.stub().copy(identifier = "changed", cacheKey = "old-ck"),
                                presentedPaywall,
                            ),
                    )
                val newConfig =
                    Config.stub().copy(
                        paywalls =
                            listOf(
                                Paywall.stub().copy(identifier = "keep", cacheKey = "keep-ck"),
                                Paywall.stub().copy(identifier = "changed", cacheKey = "new-ck"),
                                presentedPaywall.copy(cacheKey = "updated-presented-ck"),
                            ),
                    )

                val preload =
                    PaywallPreload(
                        factory = factory,
                        scope = IOScope(testDispatcher),
                        storage = storage,
                        assignments = assignments,
                        paywallManager = paywallManager,
                        track = {},
                    )

                When("removeUnusedPaywallVCsFromCache runs") {
                    preload.removeUnusedPaywallVCsFromCache(oldConfig, newConfig)

                    Then("only removed and changed, non-presented paywalls are cleared from cache") {
                        coVerify { paywallManager.removePaywallView("remove") }
                        coVerify { paywallManager.removePaywallView("changed") }
                        coVerify(exactly = 0) { paywallManager.removePaywallView("keep") }
                        coVerify(exactly = 0) { paywallManager.removePaywallView("presented") }
                    }
                }
            }
        }

    @Test
    fun `changedPaywallIds returns removed and cacheKey-changed identifiers`() {
        Given("old and new configs with removed, changed, unchanged and added paywalls") {
            val oldConfig =
                Config.stub().copy(
                    paywalls =
                        listOf(
                            Paywall.stub().copy(identifier = "keep", cacheKey = "keep-ck"),
                            Paywall.stub().copy(identifier = "remove", cacheKey = "remove-ck"),
                            Paywall.stub().copy(identifier = "changed", cacheKey = "old-ck"),
                        ),
                )
            val newConfig =
                Config.stub().copy(
                    paywalls =
                        listOf(
                            Paywall.stub().copy(identifier = "keep", cacheKey = "keep-ck"),
                            Paywall.stub().copy(identifier = "changed", cacheKey = "new-ck"),
                            Paywall.stub().copy(identifier = "added", cacheKey = "added-ck"),
                        ),
                )

            When("changedPaywallIds is computed") {
                val changed = PaywallPreload.changedPaywallIds(oldConfig, newConfig)

                Then("only removed and cacheKey-changed identifiers are returned") {
                    assertEquals(setOf("remove", "changed"), changed)
                }
            }
        }
    }

    @Test
    fun `invalidatePreloadFingerprint clears lastFingerprint`() {
        Given("a preload with a recorded fingerprint") {
            val preload =
                PaywallPreload(
                    factory = mockk(relaxed = true),
                    scope = IOScope(testDispatcher),
                    storage = mockk(relaxed = true),
                    assignments = mockk(relaxed = true),
                    paywallManager = mockk(relaxed = true),
                    track = {},
                )
            preload.lastFingerprint.set("fingerprint")

            When("invalidatePreloadFingerprint is called") {
                preload.invalidatePreloadFingerprint()

                Then("the fingerprint is cleared") {
                    assertNull(preload.lastFingerprint.get())
                }
            }
        }
    }

    @Test
    fun `preloadAllPaywalls preloads only the prioritized campaign's paywalls in the first batch`() =
        runTest(testDispatcher) {
            Given("a prioritized campaign and another campaign, both with treatment assignments") {
                val factory = mockk<PaywallPreload.Factory>(relaxed = true)
                val storage = mockk<LocalStorage>(relaxed = true)
                val assignments = mockk<Assignments>(relaxed = true)
                val paywallManager = mockk<PaywallManager>(relaxed = true)

                fun trigger(
                    eventName: String,
                    experimentId: String,
                    groupId: String,
                ) = Trigger(
                    eventName = eventName,
                    rules =
                        listOf(
                            TriggerRule.stub().apply {
                                this.experimentId = experimentId
                                this.experimentGroupId = groupId
                            },
                        ),
                )

                fun treatment(paywallId: String) =
                    Experiment.Variant(
                        "variant_$paywallId",
                        Experiment.Variant.VariantType.TREATMENT,
                        paywallId,
                    )

                val config =
                    Config.stub().copy(
                        triggers =
                            setOf(
                                trigger("prioritized_event", "prioritized_exp", "prioritized_campaign"),
                                trigger("other_event", "other_exp", "other_campaign"),
                                trigger("unconfirmed_event", "unconfirmed_exp", "third_campaign"),
                            ),
                        prioritizedCampaignId = "prioritized_campaign",
                    )
                every { storage.getConfirmedAssignments() } returns
                    mapOf(
                        "prioritized_exp" to treatment("prioritized_paywall"),
                        "other_exp" to treatment("other_paywall"),
                    )
                every { assignments.unconfirmedAssignments } returns
                    mapOf("unconfirmed_exp" to treatment("unconfirmed_paywall"))

                mockkObject(WebviewChecker)
                every { WebviewChecker.webviewExists } returns true

                val requestedPaywallIds = mutableListOf<String?>()
                every {
                    factory.makePaywallRequest(any(), any(), any(), any(), any())
                } answers {
                    requestedPaywallIds.add(secondArg<ResponseIdentifiers>().paywallId)
                    mockk(relaxed = true)
                }
                val preloadCounts = mutableListOf<Int>()

                val preload =
                    PaywallPreload(
                        factory = factory,
                        scope = IOScope(testDispatcher),
                        storage = storage,
                        assignments = assignments,
                        paywallManager = paywallManager,
                        track = { event ->
                            if (event is InternalSuperwallEvent.PaywallPreload &&
                                event.state == InternalSuperwallEvent.PaywallPreload.State.Start
                            ) {
                                preloadCounts.add(event.paywallCount)
                            }
                        },
                    )

                When("preloadAllPaywalls runs up to the delay between batches") {
                    preload.preloadAllPaywalls(config, mockk())
                    testDispatcher.scheduler.runCurrent()

                    Then("only the prioritized campaign's paywall is preloaded") {
                        assertEquals(listOf(1), preloadCounts)
                        assertEquals(listOf("prioritized_paywall"), requestedPaywallIds)
                    }
                }

                When("the remaining batch runs after the delay") {
                    testDispatcher.scheduler.advanceUntilIdle()

                    Then("it preloads only the remaining campaigns' paywalls") {
                        assertEquals(listOf(1, 2), preloadCounts)
                        assertEquals(
                            setOf("other_paywall", "unconfirmed_paywall"),
                            requestedPaywallIds.drop(1).toSet(),
                        )
                    }
                }
            }
        }
}
