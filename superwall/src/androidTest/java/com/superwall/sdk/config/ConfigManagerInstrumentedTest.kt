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

class ConfigManagerUnderTest(
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
        track = {},
        entitlements = testEntitlements,
        awaitUtilNetwork = {},
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

    @After
    fun tearDown() {
        clearMocks(dependencyContainer, manager, storage, preload, localStorage, mockNetwork)
    }
}
