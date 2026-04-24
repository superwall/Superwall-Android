package com.superwall.sdk.config

import And
import Given
import Then
import When
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.VariantOption
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.network.NetworkMock
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.CONSTANT_API_KEY
import com.superwall.sdk.storage.DisableVerboseEvents
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StorageMock
import com.superwall.sdk.storage.StoredEntitlementsByProductId
import com.superwall.sdk.storage.StoredSubscriptionStatus
import com.superwall.sdk.storage.core_data.convertToJsonElement
import com.superwall.sdk.store.Entitlements
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.web.WebPaywallRedeemer
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

open class ConfigManagerUnderTest(
    context: Context,
    storage: Storage,
    network: SuperwallAPI,
    paywallManager: PaywallManager,
    storeManager: StoreManager,
    factory: Factory,
    deviceHelper: DeviceHelper,
    assignments: Assignments,
    paywallPreload: PaywallPreload,
    ioScope: CoroutineScope,
    private val testOptions: SuperwallOptions = SuperwallOptions(),
    testEntitlements: Entitlements =
        Entitlements(
            mockk<Storage>(relaxUnitFun = true) {
                every { read(StoredSubscriptionStatus) } returns SubscriptionStatus.Unknown
                every { read(StoredEntitlementsByProductId) } returns emptyMap()
                every { read(LatestRedemptionResponse) } returns null
            },
        ),
    webRedeemer: WebPaywallRedeemer = mockk(relaxed = true),
    injectedTestMode: com.superwall.sdk.store.testmode.TestMode? = null,
    testAwaitUtilNetwork: suspend () -> Unit = {},
) : ConfigManager(
        context = context,
        storage = storage,
        network = network,
        paywallManager = paywallManager,
        storeManager = storeManager,
        factory = factory,
        deviceHelper = deviceHelper,
        options = testOptions,
        assignments = assignments,
        paywallPreload = paywallPreload,
        ioScope = IOScope(ioScope.coroutineContext),
        tracker = {},
        entitlements = testEntitlements,
        awaitUtilNetwork = testAwaitUtilNetwork,
        webPaywallRedeemer = { webRedeemer },
        testMode = injectedTestMode,
        actor = SequentialActor(
            ConfigState.None,
            IOScope(ioScope.coroutineContext),
        ),
    ) {
    fun setConfig(config: Config) {
        applyRetrievedConfigForTesting(config)
    }

    fun setState(state: ConfigState) {
        setConfigStateForTesting(state)
    }
}

@RunWith(AndroidJUnit4::class)
class ConfigManagerTests {
    val mockDeviceHelper =
        mockk<DeviceHelper> {
            every { appVersion } returns "1.0"
            every { locale } returns "en-US"
            every { deviceTier } returns Tier.MID
            coEvery { getTemplateDevice() } returns emptyMap()
            coEvery {
                getEnrichment(any(), any())
            } returns Either.Success(Enrichment.stub())
        }

    @Before
    fun setup() {
        if (!Superwall.initialized) {
            Superwall.configure(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,
                CONSTANT_API_KEY,
                options = SuperwallOptions().apply { paywalls.shouldPreload = false },
            )
        }
    }

    @Test
    fun test_confirmAssignment() =
        runTest(timeout = 5.minutes) {
            Given("we have a ConfigManager with a mock assignment") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val experimentId = "abc"
                val variantId = "def"
                val variant =
                    Experiment.Variant(
                        id = variantId,
                        type = Experiment.Variant.VariantType.TREATMENT,
                        paywallId = "jkl",
                    )
                val assignment =
                    ConfirmableAssignment(experimentId = experimentId, variant = variant)
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
                val network = NetworkMock()
                val storage = StorageMock(context = context, coroutineScope = backgroundScope)
                val assignments = Assignments(storage, network, backgroundScope)
                val preload =
                    mockk<PaywallPreload> {
                        coEvery { preloadAllPaywalls(any(), any()) } just Runs
                        coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                        coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                    }

                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeManager = dependencyContainer.storeManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                When("we confirm the assignment") {
                    assignments.confirmAssignment(assignment)
                    delay(500)
                }

                Then("the assignment should be confirmed and stored correctly") {
                    assertTrue(network.assignmentsConfirmed)
                    assertEquals(storage.getConfirmedAssignments()[experimentId], variant)
                    assertNull(configManager.unconfirmedAssignments[experimentId])
                }
            }
        }

    @Test
    fun test_loadAssignments_noConfig() =
        runTest(timeout = 5.minutes) {
            Given("we have a ConfigManager with no config") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
                val network = NetworkMock()
                val storage = StorageMock(context = context, coroutineScope = backgroundScope)
                val assignments = Assignments(storage, network, backgroundScope)
                val preload =
                    mockk<PaywallPreload> {
                        coEvery { preloadAllPaywalls(any(), any()) } just Runs
                        coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                        coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                    }

                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeManager = dependencyContainer.storeManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                Log.e("test", "test_loadAssignments_noConfig")
                When("we try to get assignments") {
                    val job =
                        launch {
                            configManager.getAssignments()
                            ensureActive()
                            assert(false) // Make sure we never get here...
                        }
                    delay(1000)
                    job.cancel()
                }

                Log.e("test", "test_loadAssignments_noConfig")
                Then("no assignments should be stored") {
                    assertTrue(storage.getConfirmedAssignments().isEmpty())
                    assertTrue(configManager.unconfirmedAssignments.isEmpty())
                }
            }
            return@runTest
        }

    @Test
    fun test_loadAssignments_noTriggers() =
        runTest(timeout = 5.minutes) {
            Given("we have a ConfigManager with a config that has no triggers") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
                val network = NetworkMock()
                val storage = StorageMock(context = context, coroutineScope = backgroundScope)
                val assignments = Assignments(storage, network, backgroundScope)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeManager = dependencyContainer.storeManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )
                configManager.setConfig(
                    Config.stub().apply { this.triggers = emptySet() },
                )

                When("we get assignments") {
                    configManager.getAssignments()
                }

                Then("no assignments should be stored") {
                    assertTrue(storage.getConfirmedAssignments().isEmpty())
                    assertTrue(configManager.unconfirmedAssignments.isEmpty())
                }
            }
        }

    @Test
    fun test_loadAssignments_saveAssignmentsFromServer() =
        runTest(timeout = 30.seconds) {
            Given("we have a ConfigManager with assignments from the server") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val network = NetworkMock()
                val storage = StorageMock(context = context, coroutineScope = backgroundScope)
                val assignmentStore = Assignments(storage, network, backgroundScope)
                val preload =
                    mockk<PaywallPreload> {
                        coEvery { preloadAllPaywalls(any(), any()) } just Runs
                        coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                        coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                    }
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = mockk(relaxed = true),
                        storeManager = mockk(relaxed = true),
                        factory = mockk(relaxed = true),
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                val variantId = "variantId"
                val experimentId = "experimentId"

                val assignments: List<Assignment> =
                    listOf(
                        Assignment(experimentId = experimentId, variantId = variantId),
                    )
                network.assignments = assignments.toMutableList()

                val variantOption = VariantOption.stub().apply { id = variantId }
                configManager.setConfig(
                    Config.stub().apply {
                        triggers =
                            setOf(
                                Trigger.stub().apply {
                                    rules =
                                        listOf(
                                            TriggerRule.stub().apply {
                                                this.experimentId = experimentId
                                                this.variants = listOf(variantOption)
                                            },
                                        )
                                },
                            )
                    },
                )

                When("we get assignments") {
                    configManager.getAssignments()
                    advanceUntilIdle()
                }

                Then("the assignments should be stored correctly") {
                    assertEquals(
                        storage.getConfirmedAssignments()[experimentId],
                        variantOption.toVariant(),
                    )
                    assertTrue(configManager.unconfirmedAssignments.isEmpty())
                }
            }
            return@runTest
        }

    @Test
    fun test_config_getter_failed_state_returns_null_and_triggers_refetch() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )


            val config = configManager.config
            advanceUntilIdle()

            assertNull(config)
            assertTrue(network.getConfigCalled)
        }

    @Test
    fun test_hasConfig_emits_when_config_is_set() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            val expected = Config.stub().copy(buildId = "has-config")

            val emitted =
                launch {
                    assertEquals(expected.buildId, configManager.hasConfig.first().buildId)
                }

            advanceUntilIdle()
            configManager.setConfig(expected)
            advanceUntilIdle()
            emitted.join()
        }

    @Test
    fun test_refreshConfiguration_without_config_does_not_hit_network() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            configManager.refreshConfiguration()

            coVerify(exactly = 0) { network.getConfig(any()) }
        }

    @Test
    fun test_refreshConfiguration_with_flag_disabled_and_force_false_does_not_hit_network() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            configManager.setConfig(Config.stub().copy(rawFeatureFlags = emptyList()))
            configManager.refreshConfiguration(force = false)

            coVerify(exactly = 0) { network.getConfig(any()) }
        }

    @Test
    fun test_refreshConfiguration_force_true_ignores_disabled_flag() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network =
                spyk(NetworkMock().apply {
                    configReturnValue = Config.stub().copy(buildId = "forced-refresh")
                })
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val paywallManager =
                mockk<PaywallManager>(relaxed = true) {
                    every { currentView } returns null
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            configManager.setConfig(Config.stub().copy(rawFeatureFlags = emptyList()))
            configManager.refreshConfiguration(force = true)

            coVerify(exactly = 1) { network.getConfig(any()) }
        }

    @Test
    fun test_reset_without_config_does_not_preload() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            configManager.reset()
            advanceUntilIdle()

            coVerify(exactly = 0) { preload.preloadAllPaywalls(any(), any()) }
            assertTrue(configManager.unconfirmedAssignments.isEmpty())
        }

    @Test
    fun test_reset_with_config_rebuilds_assignments_and_preloads() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            val variant = VariantOption.stub().apply { id = "variant-a" }
            val trigger =
                Trigger.stub().apply {
                    rules =
                        listOf(
                            TriggerRule.stub().apply {
                                experimentId = "experiment-a"
                                variants = listOf(variant)
                            },
                        )
                }
            configManager.setConfig(
                Config.stub().apply {
                    triggers = setOf(trigger)
                },
            )

            configManager.reset()
            advanceUntilIdle()

            coVerify(exactly = 1) { preload.preloadAllPaywalls(any(), context) }
            assertFalse(configManager.unconfirmedAssignments.isEmpty())
        }

    @Test
    fun test_preloadAllPaywalls_waits_for_config_then_preloads() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            val job = launch { configManager.preloadAllPaywalls() }
            advanceUntilIdle()
            coVerify(exactly = 0) { preload.preloadAllPaywalls(any(), any()) }

            val config = Config.stub().copy(buildId = "preload-all")
            configManager.setConfig(config)
            advanceUntilIdle()
            job.join()

            coVerify(exactly = 1) { preload.preloadAllPaywalls(config, context) }
        }

    @Test
    fun test_preloadPaywallsByNames_waits_for_config_then_preloads() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            val eventNames = setOf("campaign_trigger")
            val job = launch { configManager.preloadPaywallsByNames(eventNames) }
            advanceUntilIdle()
            coVerify(exactly = 0) { preload.preloadPaywallsByNames(any(), any()) }

            val config = Config.stub().copy(buildId = "preload-named")
            configManager.setConfig(config)
            advanceUntilIdle()
            job.join()

            coVerify(exactly = 1) { preload.preloadPaywallsByNames(config, eventNames) }
        }

    @Test
    fun test_fetchConfiguration_updates_trigger_cache_and_persists_feature_flags() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network =
                NetworkMock().apply {
                    configReturnValue =
                        Config.stub().copy(
                            rawFeatureFlags =
                                listOf(
                                    RawFeatureFlag("enable_config_refresh_v2", true),
                                    RawFeatureFlag("disable_verbose_events", true),
                                ),
                        )
                }
            val storage = spyk(StorageMock(context = context, coroutineScope = backgroundScope))
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }

            assertEquals(
                configManager.config?.triggers?.associateBy { it.eventName }?.keys,
                configManager.triggersByEventName.keys,
            )
            verify { storage.write(DisableVerboseEvents, true) }
            verify { storage.write(LatestConfig, any()) }
        }

    @Test
    fun test_fetchConfiguration_loads_purchased_products_when_not_in_test_mode() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val storeManager =
                mockk<StoreManager>(relaxed = true) {
                    coEvery { loadPurchasedProducts(any()) } just Runs
                    coEvery { products(any()) } returns emptySet()
                }
            val entitlements =
                Entitlements(
                    mockk<Storage>(relaxUnitFun = true) {
                        every { read(StoredSubscriptionStatus) } returns SubscriptionStatus.Unknown
                        every { read(StoredEntitlementsByProductId) } returns emptyMap()
                        every { read(LatestRedemptionResponse) } returns null
                    },
                )
            val dependencyContainer =
                mockk<DependencyContainer>(relaxed = true) {
                    every { storeManager } returns storeManager
                    coEvery { makeSessionDeviceAttributes() } returns hashMapOf()
                    coEvery { provideRuleEvaluator(any()) } returns mockk()
                    every { deviceHelper } returns mockDeviceHelper
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = mockk(relaxed = true),
                    storeManager = storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                    testEntitlements = entitlements,
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            coVerify(exactly = 1) { storeManager.loadPurchasedProducts(any()) }
        }

    @Test
    fun test_fetchConfiguration_redeems_existing_web_entitlements_when_not_in_test_mode() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val redeemer = mockk<WebPaywallRedeemer>(relaxed = true)

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                    webRedeemer = redeemer,
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            coVerify(exactly = 1) { redeemer.redeem(WebPaywallRedeemer.RedeemType.Existing) }
        }

    @Test
    fun test_fetchConfiguration_preloads_products_when_preloading_enabled() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config =
                Config.stub().copy(
                    paywalls =
                        listOf(
                            com.superwall.sdk.models.paywall.Paywall.stub().copy(productIds = listOf("prod.a", "prod.b")),
                            com.superwall.sdk.models.paywall.Paywall.stub().copy(productIds = listOf("prod.b", "prod.c")),
                        ),
                )
            val network = NetworkMock().apply { configReturnValue = config }
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val storeManager =
                mockk<StoreManager>(relaxed = true) {
                    coEvery { products(any()) } returns emptySet()
                    coEvery { loadPurchasedProducts(any()) } just Runs
                }
            val dependencyContainer =
                mockk<DependencyContainer>(relaxed = true) {
                    every { storeManager } returns storeManager
                    coEvery { makeSessionDeviceAttributes() } returns hashMapOf()
                    coEvery { provideRuleEvaluator(any()) } returns mockk()
                    every { deviceHelper } returns mockDeviceHelper
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val options =
                SuperwallOptions().apply {
                    paywalls.shouldPreload = true
                }

            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = mockk(relaxed = true),
                    storeManager = storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                    testOptions = options,
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            coVerify(exactly = 1) {
                storeManager.products(
                    match { it == setOf("prod.a", "prod.b", "prod.c") },
                )
            }
        }

    @Test
    fun test_refreshConfiguration_success_resets_request_cache_and_removes_unused_paywalls() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val oldConfig =
                Config.stub().copy(
                    buildId = "old",
                    rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)),
                )
            val newConfig =
                Config.stub().copy(
                    buildId = "new",
                    rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)),
                )
            val network = mockk<Network> {
                coEvery { getConfig(any()) } returns Either.Success(newConfig)
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(mockk())
            }
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val paywallManager =
                mockk<PaywallManager>(relaxed = true) {
                    every { currentView } returns null
                }
            val storeManager =
                mockk<StoreManager>(relaxed = true) {
                    coEvery { loadPurchasedProducts(any()) } just Runs
                }
            val paywallPreload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val dependencyContainer =
                mockk<DependencyContainer>(relaxed = true) {
                    every { paywallManager } returns paywallManager
                    every { storeManager } returns storeManager
                    every { deviceHelper } returns mockDeviceHelper
                    coEvery { makeSessionDeviceAttributes() } returns hashMapOf()
                    coEvery { provideRuleEvaluator(any()) } returns mockk()
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val configManager =
                spyk(
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = paywallManager,
                        storeManager = storeManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = paywallPreload,
                        ioScope = backgroundScope,
                    ),
                ) {
                    every { config } returns oldConfig
                }

            configManager.refreshConfiguration()
            advanceUntilIdle()

            verify(exactly = 1) { paywallManager.resetPaywallRequestCache() }
            coVerify(exactly = 1) { paywallPreload.removeUnusedPaywallVCsFromCache(oldConfig, newConfig) }
        }

    @Test
    fun test_fetchConfiguration_emits_retrieving_then_failed_without_cache() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } throws IllegalStateException("fetch failed")
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Failure(NetworkError.Unknown())
            }
            val storage = spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                every { read(LatestConfig) } returns null
                every { read(LatestEnrichment) } returns null
            }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val states = mutableListOf<ConfigState>()
            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeManager = dependencyContainer.storeManager,
                    factory = dependencyContainer,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                )

            val collectJob =
                launch {
                    configManager.configState
                        .onEach { states.add(it) }
                        .first { it is ConfigState.Failed }
                }

            configManager.fetchConfiguration()
            collectJob.join()

            assertTrue(states.any { it is ConfigState.Retrieving })
            assertTrue(states.last() is ConfigState.Failed)
        }

    @Test
    fun should_refresh_config_successfully() =
        runTest(timeout = Duration.INFINITE) {
            Given("we have a ConfigManager with an old config") {
                val mockNetwork =
                    mockk<Network> {
                        coEvery { getConfig(any()) } returns
                            Either.Success<Config, NetworkError>(
                                Config.stub(),
                            )
                        coEvery {
                            getEnrichment(
                                any(),
                                any(),
                                any(),
                            )
                        } returns Either.Success(mockk<Enrichment>())
                    }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
                val storage = StorageMock(context = context, coroutineScope = backgroundScope)
                val oldConfig =
                    Config.stub().copy(
                        rawFeatureFlags =
                            listOf(
                                RawFeatureFlag("enable_config_refresh_v2", true),
                            ),
                    )

                val mockPaywallManager =
                    mockk<PaywallManager> {
                        every { resetPaywallRequestCache() } just Runs
                        every { currentView } returns null
                    }

                val mockContainer =
                    spyk(dependencyContainer) {
                        every { deviceHelper } returns mockDeviceHelper
                        every { paywallManager } returns mockPaywallManager
                    }
                val assignments = Assignments(storage, mockNetwork, backgroundScope)

                val testId = "123"
                val configManager =
                    spyk(
                        ConfigManagerUnderTest(
                            context,
                            storage,
                            mockNetwork,
                            mockPaywallManager,
                            dependencyContainer.storeManager,
                            mockContainer,
                            mockDeviceHelper,
                            assignments = assignments,
                            paywallPreload = preload,
                            ioScope = backgroundScope,
                        ),
                    ) {
                        every { config } returns oldConfig.copy(requestId = testId)
                    }

                When("we refresh the configuration") {
                    Superwall.configure(
                        context.applicationContext as Application,
                        "pk_test_1234",
                        null,
                        null,
                        null,
                        null,
                    )
                    configManager.refreshConfiguration()
                }

                Then("the config should be refreshed and the paywall cache reset") {
                    coVerify { mockNetwork.getConfig(any()) }
                    verify { mockPaywallManager.resetPaywallRequestCache() }
                    assertTrue(configManager.config?.requestId === testId)
                }
            }
        }

    @Test
    fun should_fail_refreshing_config_and_keep_old_config() =
        runTest(timeout = Duration.INFINITE) {
            Given("we have a ConfigManager with an old config and a network that fails") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val mockNetwork =
                    mockk<Network> {
                        coEvery { getConfig(any()) } returns Either.Failure(NetworkError.Unknown())
                        coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(mockk<Enrichment>())
                    }
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
                val storage = StorageMock(context = context, coroutineScope = backgroundScope)
                val oldConfig =
                    Config.stub().copy(
                        rawFeatureFlags =
                            listOf(
                                RawFeatureFlag("enable_config_refresh_v2", true),
                            ),
                    )

                val mockPaywallManager =
                    mockk<PaywallManager> {
                        every { resetPaywallRequestCache() } just Runs
                        every { currentView } returns null
                    }

                val mockContainer =
                    spyk(dependencyContainer) {
                        every { deviceHelper } returns mockDeviceHelper
                        every { paywallManager } returns mockPaywallManager
                    }
                val assignments = Assignments(storage, mockNetwork, backgroundScope)

                val testId = "123"
                val configManager =
                    spyk(
                        ConfigManagerUnderTest(
                            context,
                            storage,
                            mockNetwork,
                            mockPaywallManager,
                            dependencyContainer.storeManager,
                            mockContainer,
                            mockDeviceHelper,
                            assignments = assignments,
                            paywallPreload = preload,
                            ioScope = backgroundScope,
                        ),
                    ) {
                        every { config } returns oldConfig.copy(requestId = testId)
                    }

                When("we try to refresh the configuration") {
                    configManager.refreshConfiguration()

                    Then("the old config should be kept") {
                        coVerify { mockNetwork.getConfig(any()) }
                        assertTrue(configManager.config?.requestId === testId)
                    }
                }
            }
        }

    private val storage =
        mockk<Storage> {
            coEvery { write(any(), any()) } just Runs
            coEvery { read(LatestRedemptionResponse) } returns null
            coEvery { read(StoredEntitlementsByProductId) } returns emptyMap()
        }
    private val dependencyContainer =
        mockk<DependencyContainer> {
            coEvery { makeSessionDeviceAttributes() } returns hashMapOf()
            coEvery { provideRuleEvaluator(any()) } returns mockk()
        }

    private val manager =
        mockk<PaywallManager> {
            every { resetPaywallRequestCache() } just Runs
            every { resetCache() } just Runs
        }
    private val storeKit =
        mockk<StoreManager> {
            coEvery { products(any()) } returns emptySet()
            coEvery { loadPurchasedProducts(any()) } just Runs
        }
    private val preload =
        mockk<PaywallPreload> {
            coEvery { preloadAllPaywalls(any(), any()) } just Runs
            coEvery { preloadPaywallsByNames(any(), any()) } just Runs
            coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
        }

    private val localStorage =
        mockk<LocalStorage> {
            every { getConfirmedAssignments() } returns emptyMap()
            every { saveConfirmedAssignments(any()) } just Runs
            coEvery { read(LatestRedemptionResponse) } returns null
            coEvery { read(StoredEntitlementsByProductId) } returns emptyMap()
        }
    private val mockNetwork = mockk<Network>()

    @Test
    fun test_network_delay_with_cached_version() =
        runTest(timeout = 5.minutes) {
            Given("we have a cached config and a delayed network response") {
                val cachedConfig =
                    Config.stub().copy(
                        buildId = "cached",
                        rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)),
                    )
                val newConfig = Config.stub().copy(buildId = "not")

                coEvery { storage.read(LatestRedemptionResponse) } returns null
                coEvery { localStorage.read(LatestRedemptionResponse) } returns null
                coEvery { storage.read(LatestConfig) } returns cachedConfig
                coEvery { storage.write(any(), any()) } just Runs
                coEvery { storage.read(LatestEnrichment) } returns Enrichment.stub()
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    delay(1200)
                    Either.Success(newConfig)
                }
                coEvery { mockNetwork.getEnrichment(any(), any(), any()) } coAnswers {
                    delay(1200)
                    Either.Success(Enrichment.stub())
                }

                coEvery {
                    mockDeviceHelper.getEnrichment(any(), any())
                } coAnswers {
                    delay(1200)
                    Either.Success(Enrichment.stub())
                }

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
                val mockContainer =
                    spyk(dependencyContainer) {
                        every { deviceHelper } returns mockDeviceHelper
                        every { paywallManager } returns manager
                    }

                val assignmentStore = Assignments(localStorage, mockNetwork, backgroundScope)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = mockContainer.paywallManager,
                        storeManager = mockContainer.storeManager,
                        factory = mockContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the cached config should be used initially") {
                        coVerify(exactly = 1) { storage.read(LatestConfig) }
                        configManager.configState.first { it is ConfigState.Retrieved }
                        assertEquals("cached", configManager.config?.buildId)
                        advanceUntilIdle()
                    }
                }
            }
        }

    @Test
    fun test_network_delay_without_cached_version() =
        runTest(timeout = 5.minutes) {
            Given("we have no cached config and a delayed network response") {
                coEvery { storage.read(LatestRedemptionResponse) } returns null
                coEvery { storage.read(LatestConfig) } returns null
                coEvery { localStorage.read(LatestRedemptionResponse) } returns null
                coEvery { localStorage.read(LatestEnrichment) } returns null
                coEvery { storage.read(LatestEnrichment) } returns null
                coEvery {
                    mockDeviceHelper.getEnrichment(any(), any())
                } returns Either.Failure(NetworkError.Unknown())
                coEvery {
                    mockNetwork.getEnrichment(any(), any(), any())
                } returns Either.Failure(NetworkError.Unknown())
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    delay(1200)
                    Either.Success(Config.stub().copy(buildId = "not"))
                }
                coEvery { mockDeviceHelper.getTemplateDevice() } returns emptyMap()

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val assignmentStore = Assignments(localStorage, mockNetwork, backgroundScope)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = manager,
                        storeManager = storeKit,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    And("we wait for it to be retrieved") {
                        configManager.configState.first { it is ConfigState.Retrieved }

                        Then("the new config should be fetched exactly once and used") {
                            coVerify(exactly = 1) { mockNetwork.getConfig(any()) }
                            assertEquals("not", configManager.config?.buildId)
                        }
                    }
                }
            }
        }

    @Test
    fun test_network_failure_with_cached_version() =
        runTest(timeout = 5.minutes) {
            Given("we have a cached config and a network failure") {
                val cachedConfig =
                    Config.stub().copy(
                        buildId = "cached",
                        rawFeatureFlags =
                            listOf(
                                RawFeatureFlag("enable_config_refresh_v2", true),
                            ),
                    )
                coEvery { storage.read(LatestRedemptionResponse) } returns null
                coEvery { localStorage.read(LatestRedemptionResponse) } returns null
                coEvery { storage.read(LatestConfig) } returns cachedConfig
                coEvery { mockNetwork.getConfig(any()) } returns Either.Failure(NetworkError.Unknown())
                coEvery { localStorage.read(LatestEnrichment) } returns null
                coEvery { storage.read(LatestEnrichment) } returns null
                coEvery {
                    mockNetwork.getEnrichment(any(), any(), any())
                } returns Either.Failure(NetworkError.Unknown())

                coEvery {
                    mockDeviceHelper.getEnrichment(any(), any())
                } returns Either.Failure(NetworkError.Unknown())

                coEvery { mockDeviceHelper.getTemplateDevice() } returns emptyMap()

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val assignmentStore = Assignments(localStorage, mockNetwork, backgroundScope)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = manager,
                        storeManager = storeKit,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the cached config should be used") {
                        configManager.configState.first { it is ConfigState.Retrieved }
                        coEvery { mockNetwork.getConfig(any()) } returns
                            Either.Success(
                                Config.stub().copy(buildId = "not"),
                            )
                        assertEquals("cached", configManager.config?.buildId)

                        And("the network becomes available and we fetch again") {
                            coEvery { mockNetwork.getConfig(any()) } returns
                                Either.Success(
                                    Config.stub().copy(buildId = "not"),
                                )

                            Then("the new config should be set and used") {
                                configManager.configState
                                    .onEach {
                                        println("$it is ${it::class}")
                                    }.drop(1)
                                    .first { it is ConfigState.Retrieved }
                                assertEquals("not", configManager.config?.buildId)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun test_quick_network_success() =
        runTest {
            Given("we have a quick network response") {
                val newConfig = Config.stub().copy(buildId = "not")
                coEvery { storage.read(LatestRedemptionResponse) } returns null
                coEvery { localStorage.read(LatestRedemptionResponse) } returns null
                coEvery { storage.read(LatestConfig) } returns null
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    delay(200)
                    Either.Success(newConfig)
                }

                coEvery { localStorage.read(LatestEnrichment) } returns null
                coEvery { storage.read(LatestEnrichment) } returns null
                coEvery {
                    mockNetwork.getEnrichment(any(), any(), any())
                } returns Either.Success(Enrichment.stub())

                coEvery {
                    mockDeviceHelper.getEnrichment(any(), any())
                } returns Either.Success(Enrichment.stub())

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val assignmentStore = Assignments(localStorage, mockNetwork, backgroundScope)
                val preload =
                    mockk<PaywallPreload> {
                        coEvery { preloadAllPaywalls(any(), any()) } just Runs
                        coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                        coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                    }

                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = manager,
                        storeManager = storeKit,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the new config should be used immediately") {
                        assertEquals("not", configManager.config?.buildId)
                    }
                }
                return@runTest
            }
        }

    @Test
    fun test_config_and_geo_calls_both_cached() =
        runTest(timeout = 500.seconds) {
            Given("we have cached config and geo info, and delayed network responses") {
                val cachedConfig =
                    Config.stub().copy(
                        buildId = "cached",
                        rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)),
                    )
                val newConfig = Config.stub().copy(buildId = "not")
                val cachedGeo = Enrichment.stub().copy()
                val newGeo = Enrichment.stub().copy(_device = JsonObject(mapOf("demandTier" to "gold".convertToJsonElement())))

                coEvery { preload.preloadAllPaywalls(any(), any()) } just Runs
                coEvery { storage.read(LatestRedemptionResponse) } returns null
                coEvery { localStorage.read(LatestRedemptionResponse) } returns null
                coEvery { storage.read(LatestConfig) } returns cachedConfig
                coEvery { storage.read(LatestEnrichment) } returns cachedGeo
                coEvery { storage.write(any(), any()) } just Runs
                coEvery { localStorage.read(LatestEnrichment) } returns cachedGeo
                every { manager.resetPaywallRequestCache() } just Runs
                coEvery { preload.removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                var callCount = 0
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    if (callCount == 0) {
                        callCount += 1
                        delay(5000)
                    }
                    Either.Success(newConfig)
                }
                var enrichmentCallCount = 0
                every { mockDeviceHelper.setEnrichment(any()) } just Runs
                coEvery { mockDeviceHelper.getEnrichment(any(), any()) } coAnswers {
                    enrichmentCallCount += 1
                    if (enrichmentCallCount == 1) {
                        delay(5000)
                        Either.Failure(NetworkError.Timeout)
                    } else {
                        delay(100)
                        Either.Success(newGeo)
                    }
                }
                coEvery { mockDeviceHelper.getTemplateDevice() } returns emptyMap()

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null, apiKey = "")

                val mockContainer =
                    spyk(dependencyContainer) {
                        every { deviceHelper } returns mockDeviceHelper
                        every { paywallManager } returns manager
                    }

                val assignmentStore = Assignments(localStorage, mockNetwork, backgroundScope)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = mockContainer.paywallManager,
                        storeManager = mockContainer.storeManager,
                        factory = mockContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = backgroundScope,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the cached config and geo info should be used initially") {
                        configManager.configState.first { it is ConfigState.Retrieved }.also {
                            assertEquals("cached", it.getConfig()?.buildId)
                        }
                        coEvery { mockNetwork.getConfig(any()) } coAnswers {
                            delay(100)
                            Either.Success(newConfig)
                        }

                        And("we wait until new config is available") {
                            configManager.configState.drop(1).first { it is ConfigState.Retrieved }

                            Then("the new config and geo info should be fetched and used") {
                                assertEquals("not", configManager.config?.buildId)
                                advanceUntilIdle()
                            }
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------
    // Regression guards added during the actor refactor follow-up work.
    // Each test calls out the specific fix it guards so future refactors
    // know what they're preserving.
    // -------------------------------------------------------------------

    private fun makeUnderTest(
        backgroundScope: CoroutineScope,
        network: SuperwallAPI,
        storage: Storage,
        assignments: Assignments,
        preload: PaywallPreload,
        deviceHelper: DeviceHelper = mockDeviceHelper,
        options: SuperwallOptions = SuperwallOptions().apply { paywalls.shouldPreload = false },
        testModeImpl: com.superwall.sdk.store.testmode.TestMode? = null,
    ): ConfigManagerUnderTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container =
            DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
        return ConfigManagerUnderTest(
            context = context,
            storage = storage,
            network = network,
            paywallManager = container.paywallManager,
            storeManager = container.storeManager,
            factory = container,
            deviceHelper = deviceHelper,
            assignments = assignments,
            paywallPreload = preload,
            ioScope = backgroundScope,
            testOptions = options,
            injectedTestMode = testModeImpl,
        )
    }

    // Test 1: isRetryingCallback plumbing — when the network layer invokes
    // the retry callback, the config actor must transition to Retrying. Pre-fix,
    // Network.getConfig swallowed the callback, so this transition never fired.
    @Test
    fun test_isRetryingCallback_invokes_Retrying_state() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val retryInvocations = java.util.concurrent.atomic.AtomicInteger(0)
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } coAnswers {
                    val cb = firstArg<suspend () -> Unit>()
                    // Simulate two retries before succeeding.
                    cb()
                    cb()
                    retryInvocations.set(2)
                    Either.Success(Config.stub())
                }
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }

            val seen = mutableListOf<ConfigState>()
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            val collector =
                launch {
                    configManager.configState
                        .onEach { seen.add(it) }
                        .first { it is ConfigState.Retrieved }
                }
            configManager.fetchConfiguration()
            collector.join()

            assertEquals(2, retryInvocations.get())
            assertTrue(
                "Expected at least one Retrying state after isRetryingCallback invocation, got $seen",
                seen.any { it is ConfigState.Retrying },
            )
        }

    // Test 2: cached-config success enqueues PreloadIfEnabled BEFORE RefreshConfig.
    // Guards the fix for the "refresh queued ahead of preload" regression.
    @Test
    fun test_cached_config_success_preloads_before_refresh() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val cached =
                Config.stub().copy(
                    buildId = "cached",
                    rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)),
                )
            val fresh = Config.stub().copy(buildId = "fresh")
            val getConfigCalls = java.util.concurrent.atomic.AtomicInteger(0)
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } coAnswers {
                    val n = getConfigCalls.incrementAndGet()
                    if (n == 1) {
                        // First call is the timed fetch inside initial FetchConfig.
                        // Make it slow enough that the cached fallback wins.
                        delay(2_000)
                        Either.Success(fresh)
                    } else {
                        Either.Success(fresh)
                    }
                }
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns cached
                    every { read(LatestEnrichment) } returns Enrichment.stub()
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val options = SuperwallOptions().apply { paywalls.shouldPreload = true }
            val configManager =
                makeUnderTest(backgroundScope, network, storage, assignments, preload, options = options)

            configManager.fetchConfiguration()
            // Wait until refresh completes (second getConfig call returns).
            configManager.configState
                .first { it is ConfigState.Retrieved && it.config.buildId == "fresh" }
            advanceUntilIdle()

            io.mockk.coVerifyOrder {
                preload.preloadAllPaywalls(any(), any())
                network.getConfig(any())
            }
            assertTrue(
                "Expected two network.getConfig calls (cached + refresh), got ${getConfigCalls.get()}",
                getConfigCalls.get() >= 2,
            )
        }

    // Test 3: cold-start failure must auto-retry FetchConfig (not no-op RefreshConfig).
    // Guards the claim-3 fix.
    @Test
    fun test_cold_start_failure_auto_retries_fetchConfig() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } coAnswers {
                    calls.incrementAndGet()
                    Either.Failure(NetworkError.Unknown())
                }
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            // Kick it off, then drain the queue for a few passes so the failure
            // handler has a chance to enqueue retries.
            configManager.fetchConfiguration()
            // Give the retry loop a couple of cycles to spin.
            delay(100)
            advanceUntilIdle()

            assertTrue(
                "Expected >=2 getConfig calls (initial + auto-retry), got ${calls.get()}",
                calls.get() >= 2,
            )
            assertTrue(configManager.configState.value is ConfigState.Failed)
        }

    // Test 4: refreshConfiguration() called while initial fetch is in-flight is a no-op.
    // Guards the fix for the redundant-refresh regression triggered by AppSessionManager.onStart.
    @Test
    fun test_refreshConfiguration_is_noop_when_no_retrieved_config() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            // Pin state to Retrieving so refreshConfiguration sees no retrieved config.
            configManager.setState(ConfigState.Retrieving)
            configManager.refreshConfiguration(force = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { network.getConfig(any()) }

            // Also verify None state is a no-op.
            configManager.setState(ConfigState.None)
            configManager.refreshConfiguration(force = false)
            advanceUntilIdle()
            coVerify(exactly = 0) { network.getConfig(any()) }
        }

    // Test 5: reevaluateTestMode observes the new state synchronously on the caller's thread.
    // If someone converts it back to an actor-queued action, this fails.
    @Test
    fun test_reevaluateTestMode_is_synchronous() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val testModeImpl =
                com.superwall.sdk.store.testmode.TestMode(storage = storage, isTestEnvironment = false)
            every { mockDeviceHelper.bundleId } returns "com.superwall.test"

            val config =
                Config.stub().copy(
                    testModeUserIds =
                        listOf(
                            com.superwall.sdk.store.testmode.models.TestStoreUser(
                                type = com.superwall.sdk.store.testmode.models.TestStoreUserType.UserId,
                                value = "test-user",
                            ),
                        ),
                )
            val configManager =
                makeUnderTest(
                    backgroundScope,
                    network,
                    storage,
                    assignments,
                    preload,
                    testModeImpl = testModeImpl,
                )

            assertFalse("Test mode should start inactive", testModeImpl.isTestMode)

            // Call reevaluateTestMode with a matching appUserId — assert state flipped
            // on the NEXT LINE (no advanceUntilIdle, no yield).
            configManager.reevaluateTestMode(config = config, appUserId = "test-user")

            assertTrue("Test mode must be active synchronously", testModeImpl.isTestMode)
        }

    // Test 6: reset() mutates assignments synchronously.
    // Guards against re-actorizing reset() without a sync contract.
    @Test
    fun test_reset_mutates_assignments_synchronously() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = NetworkMock()
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = spyk(Assignments(storage, network, backgroundScope))
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)
            configManager.setConfig(Config.stub())

            configManager.reset()
            // No advanceUntilIdle, no yield — the mutating parts must already have run.
            verify(exactly = 1) { assignments.reset() }
            verify(exactly = 1) { assignments.choosePaywallVariants(any()) }
        }

    // Test 7: concurrent fetchConfiguration() calls don't fan out to multiple network fetches.
    // The in-flight guard (Retrieving/Retrying) in fetchConfiguration() should dedup.
    @Test
    fun test_concurrent_fetchConfiguration_calls_dedup() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } coAnswers {
                    calls.incrementAndGet()
                    delay(500) // keep the first call in-flight
                    Either.Success(Config.stub())
                }
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            // Kick the first fetch on a background coroutine so we can observe
            // the Retrieving state before calling again.
            val first = launch { configManager.fetchConfiguration() }
            // Wait until the actor starts the initial fetch.
            configManager.configState.first { it is ConfigState.Retrieving }
            // Now a second call should bail out (guarded) — it must not queue
            // another FetchConfig behind the first.
            configManager.fetchConfiguration()
            first.join()

            assertEquals(
                "Expected exactly one network.getConfig while Retrieving — got ${calls.get()}",
                1,
                calls.get(),
            )
        }

    // Test 8: ApplyConfig (now a sub-action) runs all its side effects before state → Retrieved.
    // If it regresses to fire-after-Retrieved, triggersByEventName would be stale by then.
    @Test
    fun test_applyConfig_side_effects_happen_before_retrieved() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config =
                Config.stub().copy(
                    triggers = setOf(Trigger.stub().copy(eventName = "my_event")),
                    rawFeatureFlags = listOf(RawFeatureFlag("disable_verbose_events", true)),
                )
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(config)
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val triggersSnapshotOnRetrieved = mutableListOf<String>()
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            val collector =
                launch {
                    configManager.configState
                        .first { it is ConfigState.Retrieved }
                    triggersSnapshotOnRetrieved.addAll(configManager.triggersByEventName.keys)
                }
            configManager.fetchConfiguration()
            collector.join()

            assertTrue(
                "triggersByEventName must be populated by the time state → Retrieved, got $triggersSnapshotOnRetrieved",
                triggersSnapshotOnRetrieved.contains("my_event"),
            )
            verify { storage.write(DisableVerboseEvents, true) }
        }

    // Test 9: ApplyConfig only writes LatestConfig when enableConfigRefresh is true.
    // Guards the conditional branch inside ApplyConfig.
    @Test
    fun test_applyConfig_skips_latestConfig_write_when_flag_off() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // No rawFeatureFlags → enableConfigRefresh defaults to false.
            val config = Config.stub()
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(config)
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            verify(exactly = 0) { storage.write(LatestConfig, any()) }
            // Sanity: the other ApplyConfig writes still happen.
            verify { storage.write(DisableVerboseEvents, any()) }
        }

    // Test 10: getAssignments() gates on Retrieved before dispatching.
    // It must NOT dispatch the GetAssignments action while state is None,
    // or we'd deadlock the actor queue on awaitFirstValidConfig.
    @Test
    fun test_getAssignments_waits_for_retrieved_before_dispatch() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val serverAssignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager =
                makeUnderTest(backgroundScope, network, storage, serverAssignments, preload)

            // State starts at None — launch getAssignments and verify it is
            // suspended BEFORE the action runs (no server call yet).
            val gatheredJob = launch { configManager.getAssignments() }
            delay(200)
            assertTrue(
                "getAssignments should still be suspended while no Retrieved config exists",
                gatheredJob.isActive,
            )
            coVerify(exactly = 0) { network.getAssignments() }

            // Flip state to Retrieved. Now the action should proceed.
            configManager.setConfig(
                Config.stub().copy(triggers = setOf(Trigger.stub().copy(eventName = "e1"))),
            )
            gatheredJob.join()
            advanceUntilIdle()

            coVerify(atLeast = 1) { network.getAssignments() }
        }

    // ===================================================================
    // Second round: failure paths, offline gating, test-mode lifecycle,
    // minor gaps. Each test guards a specific production behavior or an
    // untested branch.
    // ===================================================================

    // ---- Config failure paths -----------------------------------------

    // Enrichment fetch fails but we have a cached enrichment → use the cache,
    // still reach Retrieved, and schedule a background enrichment retry.
    @Test
    fun test_enrichment_failure_with_cached_fallback() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val cachedEnrichment = Enrichment.stub()
            val cachedConfig =
                Config.stub().copy(rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)))
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(cachedConfig)
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Failure(NetworkError.Unknown())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns cachedConfig
                    every { read(LatestEnrichment) } returns cachedEnrichment
                }
            val helper = mockk<DeviceHelper>(relaxed = true) {
                every { appVersion } returns "1.0"
                every { locale } returns "en-US"
                every { deviceTier } returns Tier.MID
                coEvery { getTemplateDevice() } returns emptyMap()
                // Initial enrichment call fails — forces the cached-fallback branch.
                coEvery { getEnrichment(any(), any()) } returns Either.Failure(NetworkError.Unknown())
            }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager =
                makeUnderTest(backgroundScope, network, storage, assignments, preload, deviceHelper = helper)

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            verify { helper.setEnrichment(cachedEnrichment) }
            // Background enrichment retry with maxRetry=6 is scheduled.
            coVerify(atLeast = 1) { helper.getEnrichment(6, 1.seconds) }
        }

    // Enrichment fetch fails and there's no cache → config fetch still
    // succeeds, state reaches Retrieved, background retry still scheduled.
    @Test
    fun test_enrichment_failure_no_cache_still_retrieves_config() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(Config.stub())
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Failure(NetworkError.Unknown())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val helper = mockk<DeviceHelper>(relaxed = true) {
                every { appVersion } returns "1.0"
                every { locale } returns "en-US"
                every { deviceTier } returns Tier.MID
                coEvery { getTemplateDevice() } returns emptyMap()
                coEvery { getEnrichment(any(), any()) } returns Either.Failure(NetworkError.Unknown())
            }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager =
                makeUnderTest(backgroundScope, network, storage, assignments, preload, deviceHelper = helper)

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            coVerify(atLeast = 1) { helper.getEnrichment(6, 1.seconds) }
        }

    // RefreshConfig network failure must NOT downgrade state to Failed —
    // we keep serving the previously-retrieved config.
    @Test
    fun test_refreshConfig_failure_preserves_retrieved_state() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val oldConfig =
                Config.stub().copy(
                    buildId = "old",
                    rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)),
                )
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Failure(NetworkError.Unknown())
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)
            configManager.setConfig(oldConfig)

            configManager.refreshConfiguration(force = true)
            advanceUntilIdle()

            assertTrue(
                "RefreshConfig failure must preserve Retrieved(old), got ${configManager.configState.value}",
                configManager.configState.value is ConfigState.Retrieved,
            )
            assertEquals("old", configManager.config?.buildId)
        }

    // getAssignments — server error is swallowed + logged, no exception escapes
    // and state stays Retrieved.
    @Test
    fun test_getAssignments_network_error_is_swallowed() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = mockk<SuperwallAPI>(relaxed = true) {
                coEvery { getAssignments() } returns Either.Failure(NetworkError.Unknown())
            }
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)
            configManager.setConfig(
                Config.stub().copy(triggers = setOf(Trigger.stub().copy(eventName = "e1"))),
            )

            // Should complete without throwing.
            configManager.getAssignments()
            advanceUntilIdle()

            assertTrue(configManager.configState.value is ConfigState.Retrieved)
        }

    // ---- Offline / network-gating spies --------------------------------

    // The retry callback on the cached fetch path must invoke awaitUtilNetwork
    // so the SDK sits on its hands until the network is back.
    @Test
    fun test_awaitUtilNetwork_is_invoked_from_retry_callback_on_cached_path() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val cachedConfig =
                Config.stub().copy(rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh_v2", true)))
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } coAnswers {
                    val cb = firstArg<suspend () -> Unit>()
                    cb()
                    Either.Success(cachedConfig)
                }
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns cachedConfig
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val awaitCalls = java.util.concurrent.atomic.AtomicInteger(0)
            val dep =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dep.paywallManager,
                    storeManager = dep.storeManager,
                    factory = dep,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                    testOptions = SuperwallOptions().apply { paywalls.shouldPreload = false },
                    testAwaitUtilNetwork = { awaitCalls.incrementAndGet() },
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            assertTrue(
                "awaitUtilNetwork must be invoked from the cached-path retry callback; saw ${awaitCalls.get()} calls",
                awaitCalls.get() >= 1,
            )
        }

    // No cache → the retry callback takes the context.awaitUntilNetworkExists
    // branch, NOT the awaitUtilNetwork lambda. Verify the lambda is *not* called
    // and that the retry callback still fires (state briefly Retrying).
    @Test
    fun test_noncached_path_does_not_call_awaitUtilNetwork_lambda() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } coAnswers {
                    val cb = firstArg<suspend () -> Unit>()
                    cb()
                    Either.Success(Config.stub())
                }
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val awaitCalls = java.util.concurrent.atomic.AtomicInteger(0)
            val dep =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val states = mutableListOf<ConfigState>()
            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dep.paywallManager,
                    storeManager = dep.storeManager,
                    factory = dep,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                    testOptions = SuperwallOptions().apply { paywalls.shouldPreload = false },
                    testAwaitUtilNetwork = { awaitCalls.incrementAndGet() },
                )

            val collect =
                launch {
                    configManager.configState.onEach { states.add(it) }
                        .first { it is ConfigState.Retrieved }
                }
            configManager.fetchConfiguration()
            collect.join()

            assertEquals(
                "Non-cached path goes through context.awaitUntilNetworkExists, not the awaitUtilNetwork lambda",
                0,
                awaitCalls.get(),
            )
            assertTrue(
                "Retrying should still be observed when the retry callback fires on the non-cached path",
                states.any { it is ConfigState.Retrying },
            )
        }

    // ---- Minor gaps ----------------------------------------------------

    // Empty triggers → getAssignments short-circuits before the server call.
    @Test
    fun test_getAssignments_empty_triggers_is_noop() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)
            configManager.setConfig(Config.stub().copy(triggers = emptySet()))

            configManager.getAssignments()
            advanceUntilIdle()

            coVerify(exactly = 0) { network.getAssignments() }
        }

    // config getter on Retrieved must NOT dispatch FetchConfig — the side
    // effect only fires on Failed. Guards against "always-refetch" regressions.
    @Test
    fun test_config_getter_on_retrieved_does_not_dispatch_fetch() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)
            configManager.setConfig(Config.stub())

            // Access the getter multiple times — must not queue any FetchConfig.
            repeat(5) { configManager.config }
            advanceUntilIdle()

            coVerify(exactly = 0) { network.getConfig(any()) }
        }

    // options.paywalls.shouldPreload == false → PreloadIfEnabled is a no-op.
    @Test
    fun test_preloadIfEnabled_is_noop_when_shouldPreload_false() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(Config.stub())
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager =
                makeUnderTest(
                    backgroundScope,
                    network,
                    storage,
                    assignments,
                    preload,
                    options = SuperwallOptions().apply { paywalls.shouldPreload = false },
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            coVerify(exactly = 0) { preload.preloadAllPaywalls(any(), any()) }
        }

    // Test mode just-activated branch — publishes the default subscription
    // status (via entitlements) and stores the override on TestMode.
    @Test
    fun test_applyConfig_testMode_just_activated_publishes_subscription_status() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(Config.stub())
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val testModeImpl =
                com.superwall.sdk.store.testmode.TestMode(storage = storage, isTestEnvironment = false)
            val configManager =
                makeUnderTest(
                    backgroundScope,
                    network,
                    storage,
                    assignments,
                    preload,
                    // Force ALWAYS so evaluateTestMode activates without needing identity matching.
                    options =
                        SuperwallOptions().apply {
                            paywalls.shouldPreload = false
                            testModeBehavior =
                                com.superwall.sdk.store.testmode.TestModeBehavior.ALWAYS
                        },
                    testModeImpl = testModeImpl,
                )

            assertFalse("Test mode starts inactive", testModeImpl.isTestMode)
            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            assertTrue("Test mode should be active after applyConfig with ALWAYS behavior", testModeImpl.isTestMode)
            // The just-activated branch in applyConfig persists an overridden
            // status on the TestMode — even if it's Inactive (no entitlements yet).
            assertTrue(
                "Expected overriddenSubscriptionStatus to be published; was null",
                testModeImpl.overriddenSubscriptionStatus != null,
            )
        }

    // hasConfig is a take(1) flow — it must emit exactly once on the first
    // Retrieved state and never again.
    @Test
    fun test_hasConfig_emits_exactly_once() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = spyk(NetworkMock())
            val storage = StorageMock(context = context, coroutineScope = backgroundScope)
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager = makeUnderTest(backgroundScope, network, storage, assignments, preload)

            val emissions = mutableListOf<Config>()
            val collector = launch { configManager.hasConfig.onEach { emissions.add(it) }.collect {} }
            configManager.setConfig(Config.stub().copy(buildId = "first"))
            advanceUntilIdle()
            configManager.setState(ConfigState.None)
            advanceUntilIdle()
            configManager.setConfig(Config.stub().copy(buildId = "second"))
            advanceUntilIdle()
            collector.cancel()

            assertEquals(
                "hasConfig must emit exactly once (take(1)); got $emissions",
                1,
                emissions.size,
            )
            assertEquals("first", emissions.single().buildId)
        }

    // ---- Test mode lifecycle in ApplyConfig -----------------------------

    // TestMode starts Active. ApplyConfig runs with a config that doesn't match
    // AUTOMATIC criteria → deactivates + clears state + flips subscription to Inactive.
    @Test
    fun test_applyConfig_deactivates_testMode_when_user_no_longer_qualifies() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val testModeImpl =
                spyk(
                    com.superwall.sdk.store.testmode.TestMode(
                        storage = storage,
                        isTestEnvironment = false,
                    ),
                )
            // Pre-seed test mode as Active via ALWAYS behavior.
            testModeImpl.evaluateTestMode(
                Config.stub(),
                "com.app",
                null,
                null,
                testModeBehavior = com.superwall.sdk.store.testmode.TestModeBehavior.ALWAYS,
            )
            assertTrue("Test mode must be seeded Active before the fetch", testModeImpl.isTestMode)

            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(Config.stub())
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val configManager =
                makeUnderTest(
                    backgroundScope,
                    network,
                    storage,
                    assignments,
                    preload,
                    // AUTOMATIC + no testModeUserIds / no bundleIdConfig → deactivates.
                    options =
                        SuperwallOptions().apply {
                            paywalls.shouldPreload = false
                            testModeBehavior =
                                com.superwall.sdk.store.testmode.TestModeBehavior.AUTOMATIC
                        },
                    testModeImpl = testModeImpl,
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            assertFalse(
                "Test mode must deactivate when applyConfig evaluates a non-matching config",
                testModeImpl.isTestMode,
            )
            verify(atLeast = 1) { testModeImpl.clearTestModeState() }
        }

    // testMode == null — applyConfig runs cleanly and takes the non-test-mode
    // branch. storeManager.loadPurchasedProducts is invoked off-queue.
    @Test
    fun test_applyConfig_with_null_testMode_loads_purchased_products() =
        runTest(timeout = 30.seconds) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val network = mockk<SuperwallAPI> {
                coEvery { getConfig(any()) } returns Either.Success(Config.stub())
                coEvery { getEnrichment(any(), any(), any()) } returns Either.Success(Enrichment.stub())
            }
            val storage =
                spyk(StorageMock(context = context, coroutineScope = backgroundScope)) {
                    every { read(LatestConfig) } returns null
                    every { read(LatestEnrichment) } returns null
                }
            val storeManager =
                mockk<StoreManager>(relaxed = true) {
                    coEvery { loadPurchasedProducts(any()) } just Runs
                    coEvery { products(any()) } returns emptySet()
                }
            val assignments = Assignments(storage, network, backgroundScope)
            val preload =
                mockk<PaywallPreload> {
                    coEvery { preloadAllPaywalls(any(), any()) } just Runs
                    coEvery { preloadPaywallsByNames(any(), any()) } just Runs
                    coEvery { removeUnusedPaywallVCsFromCache(any(), any()) } just Runs
                }
            val dep =
                DependencyContainer(context, null, null, activityProvider = null, apiKey = "")
            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dep.paywallManager,
                    storeManager = storeManager,
                    factory = dep,
                    deviceHelper = mockDeviceHelper,
                    assignments = assignments,
                    paywallPreload = preload,
                    ioScope = backgroundScope,
                    testOptions = SuperwallOptions().apply { paywalls.shouldPreload = false },
                    injectedTestMode = null, // explicitly null
                )

            configManager.fetchConfiguration()
            configManager.configState.first { it is ConfigState.Retrieved }
            advanceUntilIdle()

            coVerify(atLeast = 1) { storeManager.loadPurchasedProducts(any()) }
        }

    @After
    fun tearDown() {
        clearMocks(dependencyContainer, manager, storage, preload, localStorage, mockNetwork)
    }
}
