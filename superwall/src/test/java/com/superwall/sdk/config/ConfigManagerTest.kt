package com.superwall.sdk.config

import android.content.Context
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestMode
import com.superwall.sdk.store.testmode.TestModeBehavior
import com.superwall.sdk.web.WebPaywallRedeemer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Unit-test counterpart to ConfigManagerInstrumentedTest. Covers the same
 * Action/Reducer flows without an emulator — every collaborator is mocked,
 * including Context (which is never dereferenced by actions).
 */
class ConfigManagerTest {
    private fun config(
        buildId: String = "stub",
        triggers: Set<Trigger> = emptySet(),
        enableRefresh: Boolean = false,
    ): Config =
        Config.stub().copy(
            buildId = buildId,
            triggers = triggers,
            rawFeatureFlags =
                if (enableRefresh) {
                    listOf(RawFeatureFlag("enable_config_refresh_v2", true))
                } else {
                    emptyList()
                },
        )

    private data class Setup(
        val manager: ConfigManagerForTest,
        val network: SuperwallAPI,
        val storage: Storage,
        val preload: PaywallPreload,
        val storeManager: StoreManager,
        val webRedeemer: WebPaywallRedeemer,
        val deviceHelper: DeviceHelper,
        val testMode: TestMode?,
        val tracked: CopyOnWriteArrayList<TrackableSuperwallEvent>,
        val statuses: MutableList<SubscriptionStatus>,
        val activateCalls: AtomicInteger,
    )

    @Suppress("LongParameterList")
    private fun setup(
        scope: CoroutineScope,
        cachedConfig: Config? = null,
        cachedEnrichment: Enrichment? = null,
        networkConfig: Either<Config, NetworkError> = Either.Success(Config.stub()),
        networkConfigAnswer: (suspend (suspend () -> Unit) -> Either<Config, NetworkError>)? = null,
        deviceTier: Tier = Tier.MID,
        shouldPreload: Boolean = false,
        testModeBehavior: TestModeBehavior = TestModeBehavior.AUTOMATIC,
        injectedTestMode: TestMode? = null,
        assignments: Assignments = mockk(relaxed = true),
    ): Setup {
        val context = mockk<Context>(relaxed = true)
        val storage =
            mockk<Storage>(relaxed = true) {
                every { read(LatestConfig) } returns cachedConfig
                every { read(LatestEnrichment) } returns cachedEnrichment
                every { write(any(), any<Any>()) } just Runs
            }
        val network =
            mockk<SuperwallAPI> {
                if (networkConfigAnswer != null) {
                    coEvery { getConfig(any()) } coAnswers {
                        networkConfigAnswer.invoke(firstArg())
                    }
                } else {
                    coEvery { getConfig(any()) } returns networkConfig
                }
                coEvery { getEnrichment(any(), any(), any()) } returns
                    Either.Success(Enrichment.stub())
            }
        val deviceHelper =
            mockk<DeviceHelper>(relaxed = true) {
                every { appVersion } returns "1.0"
                every { locale } returns "en-US"
                every { this@mockk.deviceTier } returns deviceTier
                every { bundleId } returns "com.test"
                every { setEnrichment(any()) } just Runs
                coEvery { getTemplateDevice() } returns emptyMap()
                coEvery { getEnrichment(any(), any()) } returns Either.Success(Enrichment.stub())
            }
        val storeManager =
            mockk<StoreManager>(relaxed = true) {
                coEvery { products(any()) } returns emptySet()
                coEvery { loadPurchasedProducts(any()) } just Runs
            }
        val preload =
            mockk<PaywallPreload> {
                coEvery { preloadAllPaywalls(any(), any()) } just Runs
                coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
            }
        val paywallManager = mockk<PaywallManager>(relaxed = true)
        val webRedeemer = mockk<WebPaywallRedeemer>(relaxed = true)
        val factory =
            mockk<ConfigManager.Factory>(relaxed = true) {
                coEvery { makeSessionDeviceAttributes() } returns HashMap()
                every { makeHasExternalPurchaseController() } returns false
            }
        val entitlements = mockk<Entitlements>(relaxed = true)
        every { entitlements.status } returns
            kotlinx.coroutines.flow.MutableStateFlow(SubscriptionStatus.Unknown)
        every { entitlements.entitlementsByProductId } returns emptyMap()

        val tracked = CopyOnWriteArrayList<TrackableSuperwallEvent>()
        val statuses = mutableListOf<SubscriptionStatus>()
        val activateCalls = AtomicInteger(0)

        val options =
            SuperwallOptions().apply {
                paywalls.shouldPreload = shouldPreload
                this.testModeBehavior = testModeBehavior
            }

        val manager =
            ConfigManagerForTest(
                context = context,
                storage = storage,
                network = network,
                deviceHelper = deviceHelper,
                paywallManager = paywallManager,
                storeManager = storeManager,
                preload = preload,
                webRedeemer = webRedeemer,
                factory = factory,
                entitlements = entitlements,
                assignments = assignments,
                options = options,
                ioScope = scope,
                testMode = injectedTestMode,
                tracker = { tracked.add(it) },
                setSubscriptionStatus = { statuses.add(it) },
                activateTestMode = { _, justActivated ->
                    if (justActivated) activateCalls.incrementAndGet()
                },
            )
        return Setup(
            manager,
            network,
            storage,
            preload,
            storeManager,
            webRedeemer,
            deviceHelper,
            injectedTestMode,
            tracked,
            statuses,
            activateCalls,
        )
    }

    // ---- autoRetryCount lifecycle --------------------------------------

    @Test
    fun `autoRetryCount resets after a successful apply`() =
        runTest(timeout = 30.seconds) {
            val calls = AtomicInteger(0)
            val s =
                setup(
                    backgroundScope,
                    networkConfigAnswer = {
                        when (calls.incrementAndGet()) {
                            1, 2 -> Either.Failure(NetworkError.Unknown())
                            3 -> Either.Success(Config.stub())
                            else -> Either.Failure(NetworkError.Unknown())
                        }
                    },
                )

            s.manager.fetchConfiguration()
            advanceUntilIdle()
            assertEquals("Cold-start = initial + 1 retry", 2, calls.get())
            assertTrue(s.manager.configState.value is ConfigState.Failed)

            s.manager.fetchConfiguration()
            advanceUntilIdle()
            assertEquals(3, calls.get())
            assertTrue(s.manager.configState.value is ConfigState.Retrieved)

            s.manager.fetchConfiguration()
            advanceUntilIdle()
            assertEquals("Counter must reset on Retrieved — fresh budget", 5, calls.get())
        }

    // ---- reevaluateTestMode three branches -----------------------------

    @Test
    fun `reevaluateTestMode deactivates when user no longer qualifies`() =
        runTest(timeout = 30.seconds) {
            val storageForTm = mockk<Storage>(relaxed = true)
            val testMode = spyk(TestMode(storage = storageForTm, isTestEnvironment = false))
            testMode.evaluateTestMode(
                Config.stub(),
                "com.test",
                null,
                null,
                testModeBehavior = TestModeBehavior.ALWAYS,
            )
            assertTrue(testMode.isTestMode)

            val s = setup(backgroundScope, injectedTestMode = testMode)
            s.manager.reevaluateTestMode(config = Config.stub(), appUserId = "no-match")

            assertFalse(testMode.isTestMode)
            verify(atLeast = 1) { testMode.clearTestModeState() }
            assertTrue(
                "Expected SubscriptionStatus.Inactive on deactivation",
                s.statuses.any { it is SubscriptionStatus.Inactive },
            )
        }

    @Test
    fun `reevaluateTestMode activates when user now qualifies`() =
        runTest(timeout = 30.seconds) {
            val storageForTm = mockk<Storage>(relaxed = true)
            val testMode = TestMode(storage = storageForTm, isTestEnvironment = false)
            assertFalse(testMode.isTestMode)

            val s =
                setup(
                    backgroundScope,
                    testModeBehavior = TestModeBehavior.ALWAYS,
                    injectedTestMode = testMode,
                )
            s.manager.reevaluateTestMode(config = Config.stub(), appUserId = "anyone")
            advanceUntilIdle()

            assertTrue(testMode.isTestMode)
            assertEquals("activateTestMode lambda must fire once", 1, s.activateCalls.get())
        }

    @Test
    fun `reevaluateTestMode is noop when state unchanged`() =
        runTest(timeout = 30.seconds) {
            val storageForTm = mockk<Storage>(relaxed = true)
            val testMode = spyk(TestMode(storage = storageForTm, isTestEnvironment = false))
            assertFalse(testMode.isTestMode)

            val s =
                setup(
                    backgroundScope,
                    testModeBehavior = TestModeBehavior.NEVER,
                    injectedTestMode = testMode,
                )
            s.manager.reevaluateTestMode(config = Config.stub(), appUserId = "anyone")
            advanceUntilIdle()

            assertFalse(testMode.isTestMode)
            verify(exactly = 0) { testMode.clearTestModeState() }
            assertTrue("No subscription status published on no-op", s.statuses.isEmpty())
            assertEquals("activateTestMode must not fire on no-op", 0, s.activateCalls.get())
        }

    // ---- test-mode initial-fetch path skips ----------------------------

    @Test
    fun `fetchConfig in test mode skips web entitlements and product preload`() =
        runTest(timeout = 30.seconds) {
            val storageForTm = mockk<Storage>(relaxed = true)
            val testMode = TestMode(storage = storageForTm, isTestEnvironment = false)
            val s =
                setup(
                    backgroundScope,
                    shouldPreload = true,
                    testModeBehavior = TestModeBehavior.ALWAYS,
                    injectedTestMode = testMode,
                )

            s.manager.fetchConfiguration()
            s.manager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            assertTrue(testMode.isTestMode)
            coVerify(exactly = 0) { s.storeManager.products(any()) }
            coVerify(exactly = 0) { s.webRedeemer.redeem(any()) }
        }

    // ---- RefreshConfig fan-out -----------------------------------------

    @Test
    fun `refreshConfig runs full applyConfig fanout`() =
        runTest(timeout = 30.seconds) {
            val initial = config(buildId = "initial", triggers = setOf(Trigger.stub().copy(eventName = "trigger_a")), enableRefresh = true)
            val refreshed = config(buildId = "refreshed", triggers = setOf(Trigger.stub().copy(eventName = "trigger_b")), enableRefresh = true)
            val getCalls = AtomicInteger(0)
            val storageForTm = mockk<Storage>(relaxed = true)
            val testMode = spyk(TestMode(storage = storageForTm, isTestEnvironment = false))

            val s =
                setup(
                    backgroundScope,
                    networkConfigAnswer = {
                        Either.Success(if (getCalls.incrementAndGet() == 1) initial else refreshed)
                    },
                    injectedTestMode = testMode,
                )

            s.manager.fetchConfiguration()
            s.manager.configState.first { (it as? ConfigState.Retrieved)?.config?.buildId == "initial" }
            advanceUntilIdle()
            assertTrue(s.manager.triggersByEventName.containsKey("trigger_a"))

            s.manager.refreshConfiguration(force = true)
            s.manager.configState.first { (it as? ConfigState.Retrieved)?.config?.buildId == "refreshed" }
            advanceUntilIdle()

            assertTrue(s.manager.triggersByEventName.containsKey("trigger_b"))
            assertFalse(s.manager.triggersByEventName.containsKey("trigger_a"))
            verify(atLeast = 2) { testMode.evaluateTestMode(any(), any(), any(), any(), any()) }
            verify { s.storage.write(LatestConfig, refreshed) }
        }

    // ---- public preload bypasses tier gate -----------------------------

    @Test
    fun `preloadAllPaywalls bypasses shouldPreload gate`() =
        runTest(timeout = 30.seconds) {
            val s = setup(backgroundScope, shouldPreload = false)
            s.manager.applyRetrievedConfigForTesting(Config.stub())

            s.manager.preloadAllPaywalls()
            advanceUntilIdle()

            coVerify(exactly = 1) { s.preload.preloadAllPaywalls(any(), any()) }
        }

    @Test
    fun `preloadPaywallsByNames bypasses shouldPreload gate`() =
        runTest(timeout = 30.seconds) {
            val s = setup(backgroundScope, shouldPreload = false)
            s.manager.applyRetrievedConfigForTesting(Config.stub())

            s.manager.preloadPaywallsByNames(setOf("evt"))
            advanceUntilIdle()

            coVerify(exactly = 1) { s.preload.preloadPaywallsByNames(any(), eq(setOf("evt"))) }
        }

    // ---- tracking emissions --------------------------------------------

    @Test
    fun `tracking emits ConfigRefresh isCached false and DeviceAttributes on fresh fetch`() =
        runTest(timeout = 30.seconds) {
            val s =
                setup(
                    backgroundScope,
                    networkConfig = Either.Success(Config.stub().copy(buildId = "fresh-id")),
                )

            s.manager.fetchConfiguration()
            s.manager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            val refresh = s.tracked.filterIsInstance<InternalSuperwallEvent.ConfigRefresh>()
            assertTrue("Expected ConfigRefresh event, got ${s.tracked}", refresh.isNotEmpty())
            assertFalse("Fresh fetch must mark isCached=false", refresh.last().isCached)
            assertEquals("fresh-id", refresh.last().buildId)

            val deviceAttrs = s.tracked.filterIsInstance<InternalSuperwallEvent.DeviceAttributes>()
            assertTrue("Expected at least one DeviceAttributes event", deviceAttrs.isNotEmpty())
        }

    @Test
    fun `tracking emits ConfigRefresh isCached true on cached path`() =
        runTest(timeout = 30.seconds) {
            val cached = config(buildId = "cached-id", enableRefresh = true)
            val s =
                setup(
                    backgroundScope,
                    cachedConfig = cached,
                    cachedEnrichment = Enrichment.stub(),
                    // network failure on cached-refresh path → FetchConfig's
                    // `.into { if (Failure) Success(oldConfig) }` falls back to cache.
                    networkConfig = Either.Failure(NetworkError.Unknown()),
                )

            s.manager.fetchConfiguration()
            s.manager.configState.first { (it as? ConfigState.Retrieved)?.config?.buildId == "cached-id" }
            advanceUntilIdle()

            val refresh = s.tracked.filterIsInstance<InternalSuperwallEvent.ConfigRefresh>()
            assertTrue(
                "Cached path must publish ConfigRefresh with isCached=true",
                refresh.any { it.isCached && it.buildId == "cached-id" },
            )
        }

    @Test
    fun `tracking emits ConfigFail on failure without cache`() =
        runTest(timeout = 30.seconds) {
            val s =
                setup(
                    backgroundScope,
                    networkConfig = Either.Failure(NetworkError.Unknown()),
                )

            s.manager.fetchConfiguration()
            advanceUntilIdle()

            val fails = s.tracked.filterIsInstance<InternalSuperwallEvent.ConfigFail>()
            assertTrue("Expected at least one ConfigFail, got ${s.tracked}", fails.isNotEmpty())
        }

    // ---- FetchConfig dedup against Retrying ----------------------------

    @Test
    fun `fetchConfig skips when already in Retrying state`() =
        runTest(timeout = 30.seconds) {
            val calls = AtomicInteger(0)
            val s =
                setup(
                    backgroundScope,
                    networkConfigAnswer = { retryCb ->
                        calls.incrementAndGet()
                        retryCb() // flip to Retrying
                        delay(800)
                        Either.Success(Config.stub())
                    },
                )

            val first = launch { s.manager.fetchConfiguration() }
            s.manager.configState.first { it is ConfigState.Retrying }
            s.manager.fetchConfiguration() // must early-return
            first.join()

            assertEquals("Expected exactly one network.getConfig", 1, calls.get())
        }

    // ---- enrichment success cache write --------------------------------

    @Test
    fun `enrichment success writes LatestEnrichment on cached boot`() =
        runTest(timeout = 30.seconds) {
            val freshEnrichment = Enrichment.stub()
            val cached = config(enableRefresh = true)
            val s =
                setup(
                    backgroundScope,
                    cachedConfig = cached,
                    cachedEnrichment = null,
                )
            // The shared mock returns Enrichment.stub() — verify the write path fires.
            s.manager.fetchConfiguration()
            s.manager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            verify(atLeast = 1) { s.storage.write(LatestEnrichment, freshEnrichment) }
        }
}

/**
 * Test-only ConfigManager subclass that hardwires the actor and exposes the
 * test-only `applyRetrievedConfigForTesting` helper.
 */
internal class ConfigManagerForTest(
    context: Context,
    storage: Storage,
    network: SuperwallAPI,
    deviceHelper: DeviceHelper,
    paywallManager: PaywallManager,
    storeManager: StoreManager,
    preload: PaywallPreload,
    webRedeemer: WebPaywallRedeemer,
    factory: ConfigManager.Factory,
    entitlements: Entitlements,
    assignments: Assignments,
    options: SuperwallOptions,
    ioScope: CoroutineScope,
    testMode: TestMode?,
    tracker: suspend (TrackableSuperwallEvent) -> Unit,
    setSubscriptionStatus: ((SubscriptionStatus) -> Unit)?,
    activateTestMode: suspend (Config, Boolean) -> Unit,
) : ConfigManager(
        context = context,
        storeManager = storeManager,
        entitlements = entitlements,
        storage = storage,
        network = network,
        deviceHelper = deviceHelper,
        options = options,
        paywallManager = paywallManager,
        webPaywallRedeemer = { webRedeemer },
        factory = factory,
        assignments = assignments,
        paywallPreload = preload,
        ioScope = IOScope(Dispatchers.Unconfined),
        tracker = tracker,
        testMode = testMode,
        setSubscriptionStatus = setSubscriptionStatus,
        awaitUtilNetwork = {}, // no Context-based default
        activateTestMode = activateTestMode,
        actor = SequentialActor(ConfigState.None, CoroutineScope(Dispatchers.Unconfined)),
    )
