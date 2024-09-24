package com.superwall.sdk.config

import And
import Given
import Then
import When
import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.Superwall
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.models.getConfig
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.RawFeatureFlag
import com.superwall.sdk.models.geo.GeoInfo
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
import com.superwall.sdk.storage.LatestConfig
import com.superwall.sdk.storage.LatestGeoInfo
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StorageMock
import com.superwall.sdk.store.StoreKitManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration

class ConfigManagerUnderTest(
    private val context: Context,
    private val storage: Storage,
    private val network: SuperwallAPI,
    private val paywallManager: PaywallManager,
    private val storeKitManager: StoreKitManager,
    private val factory: Factory,
    private val deviceHelper: DeviceHelper,
    private val assignments: Assignments,
    private val paywallPreload: PaywallPreload,
    private val ioScope: CoroutineScope,
) : ConfigManager(
        context = context,
        storage = storage,
        network = network,
        paywallManager = paywallManager,
        storeKitManager = storeKitManager,
        factory = factory,
        deviceHelper = deviceHelper,
        options = SuperwallOptions(),
        assignments = assignments,
        paywallPreload = paywallPreload,
        ioScope = ioScope,
        track = {},
    ) {
    suspend fun setConfig(config: Config) {
        configState.emit(ConfigState.Retrieved(config))
    }
}

@RunWith(AndroidJUnit4::class)
class ConfigManagerTests {
    val mockDeviceHelper =
        mockk<DeviceHelper> {
            every { appVersion } returns "1.0"
            every { locale } returns "en-US"
            coEvery { getTemplateDevice() } returns emptyMap()
        }

    @Test
    fun test_confirmAssignment() =
        runTest {
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
                    DependencyContainer(context, null, null, activityProvider = null)
                val network = NetworkMock()
                val storage = StorageMock(context = context)
                val assignments = Assignments(storage, network, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        storage,
                        assignments,
                        dependencyContainer.paywallManager,
                    )
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeKitManager = dependencyContainer.storeKitManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = preload,
                        ioScope = this@runTest,
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
        runTest {
            Given("we have a ConfigManager with no config") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)
                val network = NetworkMock()
                val storage = StorageMock(context = context)
                val assignments = Assignments(storage, network, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        storage,
                        assignments,
                        dependencyContainer.paywallManager,
                    )

                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeKitManager = dependencyContainer.storeKitManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = preload,
                        ioScope = this@runTest,
                    )

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

                Then("no assignments should be stored") {
                    assertTrue(storage.getConfirmedAssignments().isEmpty())
                    assertTrue(configManager.unconfirmedAssignments.isEmpty())
                }
            }
        }

    @Test
    fun test_loadAssignments_noTriggers() =
        runTest {
            Given("we have a ConfigManager with a config that has no triggers") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)
                val network = NetworkMock()
                val storage = StorageMock(context = context)
                val assignments = Assignments(storage, network, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        storage,
                        assignments,
                        dependencyContainer.paywallManager,
                    )
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeKitManager = dependencyContainer.storeKitManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignments,
                        paywallPreload = preload,
                        ioScope = this@runTest,
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
        runTest {
            Given("we have a ConfigManager with assignments from the server") {
                // get context
                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)
                val network = NetworkMock()
                val storage = StorageMock(context = context)
                val assignmentStore = Assignments(storage, network, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        storage,
                        assignmentStore,
                        dependencyContainer.paywallManager,
                    )
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = network,
                        paywallManager = dependencyContainer.paywallManager,
                        storeKitManager = dependencyContainer.storeKitManager,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = this@runTest,
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
                    delay(1)
                }

                Then("the assignments should be stored correctly") {
                    assertEquals(
                        storage.getConfirmedAssignments()[experimentId],
                        variantOption.toVariant(),
                    )
                    assertTrue(configManager.unconfirmedAssignments.isEmpty())
                }
            }
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
                        coEvery { getGeoInfo() } returns Either.Success(mockk<GeoInfo>())
                    }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)
                val storage = StorageMock(context = context)
                val oldConfig =
                    Config.stub().copy(
                        rawFeatureFlags =
                            listOf(
                                RawFeatureFlag("enable_config_refresh", true),
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
                val assignments = Assignments(storage, mockNetwork, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        storage,
                        assignments,
                        dependencyContainer.paywallManager,
                    )

                val testId = "123"
                val configManager =
                    spyk(
                        ConfigManagerUnderTest(
                            context,
                            storage,
                            mockNetwork,
                            mockPaywallManager,
                            dependencyContainer.storeKitManager,
                            mockContainer,
                            mockDeviceHelper,
                            assignments = assignments,
                            paywallPreload = preload,
                            ioScope = this@runTest,
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
                        coEvery { getGeoInfo() } returns Either.Success(mockk<GeoInfo>())
                    }
                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)
                val storage = StorageMock(context = context)
                val oldConfig =
                    Config.stub().copy(
                        rawFeatureFlags =
                            listOf(
                                RawFeatureFlag("enable_config_refresh", true),
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
                val assignments = Assignments(storage, mockNetwork, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        storage,
                        assignments,
                        dependencyContainer.paywallManager,
                    )

                val testId = "123"
                val configManager =
                    spyk(
                        ConfigManagerUnderTest(
                            context,
                            storage,
                            mockNetwork,
                            mockPaywallManager,
                            dependencyContainer.storeKitManager,
                            mockContainer,
                            mockDeviceHelper,
                            assignments = assignments,
                            paywallPreload = preload,
                            ioScope = this@runTest,
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
        }
    private val dependencyContainer =
        mockk<DependencyContainer> {
            coEvery { makeSessionDeviceAttributes() } returns hashMapOf()
            coEvery { provideJavascriptEvaluator(any()) } returns mockk()
        }

    private val manager =
        mockk<PaywallManager> {
            every { resetPaywallRequestCache() } just Runs
            every { resetCache() } just Runs
        }
    private val storeKit =
        mockk<StoreKitManager> {
            coEvery { products(any()) } returns emptySet()
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
        }
    private val mockNetwork = mockk<Network>()

    @Test
    fun test_network_delay_with_cached_version() =
        runTest {
            Given("we have a cached config and a delayed network response") {
                val cachedConfig =
                    Config.stub().copy(
                        buildId = "cached",
                        rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh", true)),
                    )
                val newConfig = Config.stub().copy(buildId = "not")

                coEvery { storage.read(LatestConfig) } returns cachedConfig
                coEvery { storage.write(any(), any()) } just Runs
                coEvery { storage.read(LatestGeoInfo) } returns GeoInfo.stub()
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    delay(1200)
                    Either.Success(newConfig)
                }
                coEvery { mockNetwork.getGeoInfo() } coAnswers {
                    delay(1200)
                    Either.Success(GeoInfo.stub())
                }

                coEvery {
                    mockDeviceHelper.getGeoInfo()
                } coAnswers {
                    delay(1200)
                    Either.Success(GeoInfo.stub())
                }

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)
                val mockContainer =
                    spyk(dependencyContainer) {
                        every { deviceHelper } returns mockDeviceHelper
                        every { paywallManager } returns manager
                    }

                val assignmentStore = Assignments(localStorage, mockNetwork, this@runTest)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = mockContainer.paywallManager,
                        storeKitManager = mockContainer.storeKitManager,
                        factory = mockContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = this@runTest,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the cached config should be used initially") {
                        coVerify(exactly = 1) { storage.read(LatestConfig) }
                        configManager.configState.first { it is ConfigState.Retrieved }
                        assertEquals("cached", configManager.config?.buildId)

                        And("we wait for new config to be retrieved") {
                            configManager.configState.drop(1).first { it is ConfigState.Retrieved }

                            Then("the new config should be fetched and used") {
                                assertEquals("not", configManager.config?.buildId)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun test_network_delay_without_cached_version() =
        runTest {
            Given("we have no cached config and a delayed network response") {
                coEvery { storage.read(LatestConfig) } returns null
                coEvery { localStorage.read(LatestGeoInfo) } returns null
                coEvery { storage.read(LatestGeoInfo) } returns null
                coEvery {
                    mockDeviceHelper.getGeoInfo()
                } returns Either.Failure(NetworkError.Unknown())
                coEvery {
                    mockNetwork.getGeoInfo()
                } returns Either.Failure(NetworkError.Unknown())
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    delay(1200)
                    Either.Success(Config.stub().copy(buildId = "not"))
                }
                coEvery { mockDeviceHelper.getTemplateDevice() } returns emptyMap()

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val assignmentStore = Assignments(localStorage, mockNetwork, this@runTest)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = manager,
                        storeKitManager = storeKit,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = this@runTest,
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
        runTest {
            Given("we have a cached config and a network failure") {
                val cachedConfig =
                    Config.stub().copy(
                        buildId = "cached",
                        rawFeatureFlags =
                            listOf(
                                RawFeatureFlag("enable_config_refresh", true),
                            ),
                    )

                coEvery { storage.read(LatestConfig) } returns cachedConfig
                coEvery { mockNetwork.getConfig(any()) } returns Either.Failure(NetworkError.Unknown())
                coEvery { localStorage.read(LatestGeoInfo) } returns null
                coEvery { storage.read(LatestGeoInfo) } returns null
                coEvery {
                    mockNetwork.getGeoInfo()
                } returns Either.Failure(NetworkError.Unknown())

                coEvery {
                    mockDeviceHelper.getGeoInfo()
                } returns Either.Failure(NetworkError.Unknown())

                coEvery { mockDeviceHelper.getTemplateDevice() } returns emptyMap()

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val assignmentStore = Assignments(localStorage, mockNetwork, this@runTest)
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = manager,
                        storeKitManager = storeKit,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = this@runTest,
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
                                    .drop(1)
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

                coEvery { storage.read(LatestConfig) } returns null
                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    delay(200)
                    Either.Success(newConfig)
                }

                coEvery { localStorage.read(LatestGeoInfo) } returns null
                coEvery { storage.read(LatestGeoInfo) } returns null
                coEvery {
                    mockNetwork.getGeoInfo()
                } returns Either.Success(GeoInfo.stub())

                coEvery {
                    mockDeviceHelper.getGeoInfo()
                } returns Either.Success(GeoInfo.stub())

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val assignmentStore = Assignments(localStorage, mockNetwork, this@runTest)
                val preload =
                    PaywallPreload(
                        dependencyContainer,
                        this@runTest,
                        localStorage,
                        assignmentStore,
                        manager,
                    )
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = manager,
                        storeKitManager = storeKit,
                        factory = dependencyContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = this@runTest,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the new config should be used immediately") {
                        assertEquals("not", configManager.config?.buildId)
                    }
                }
            }
        }

    @Test
    fun test_config_and_geo_calls_both_cached() =
        runTest {
            Given("we have cached config and geo info, and delayed network responses") {
                val cachedConfig =
                    Config.stub().copy(
                        buildId = "cached",
                        rawFeatureFlags = listOf(RawFeatureFlag("enable_config_refresh", true)),
                    )
                val newConfig = Config.stub().copy(buildId = "not")
                val cachedGeo = GeoInfo.stub().copy(country = "cachedCountry")
                val newGeo = GeoInfo.stub().copy(country = "newCountry")

                coEvery { storage.read(LatestConfig) } returns cachedConfig
                coEvery { storage.read(LatestGeoInfo) } returns cachedGeo
                coEvery { localStorage.read(LatestGeoInfo) } returns cachedGeo

                coEvery { mockNetwork.getConfig(any()) } coAnswers {
                    async(Dispatchers.IO) {
                        delay(1200)
                    }.await()
                    Either.Success(newConfig)
                }
                coEvery { mockDeviceHelper.getGeoInfo() } coAnswers {
                    async(Dispatchers.IO) {
                        delay(1200)
                    }.await()
                    Either.Success(newGeo)
                }
                coEvery { mockDeviceHelper.getTemplateDevice() } returns emptyMap()

                val context = InstrumentationRegistry.getInstrumentation().targetContext

                val dependencyContainer =
                    DependencyContainer(context, null, null, activityProvider = null)

                val mockContainer =
                    spyk(dependencyContainer) {
                        every { deviceHelper } returns mockDeviceHelper
                        every { paywallManager } returns manager
                    }
                val assignmentStore = Assignments(localStorage, mockNetwork, this@runTest)
                val preload =
                    PaywallPreload(
                        mockContainer,
                        this@runTest,
                        localStorage,
                        assignmentStore,
                        dependencyContainer.paywallManager,
                    )
                val configManager =
                    ConfigManagerUnderTest(
                        context = context,
                        storage = storage,
                        network = mockNetwork,
                        paywallManager = mockContainer.paywallManager,
                        storeKitManager = mockContainer.storeKitManager,
                        factory = mockContainer,
                        deviceHelper = mockDeviceHelper,
                        assignments = assignmentStore,
                        paywallPreload = preload,
                        ioScope = this@runTest,
                    )

                When("we fetch the configuration") {
                    configManager.fetchConfiguration()

                    Then("the cached config and geo info should be used initially") {
                        configManager.configState.first { it is ConfigState.Retrieved }.also {
                            assertEquals("cached", it.getConfig()?.buildId)
                        }

                        And("we wait until new config is available") {
                            configManager.configState.drop(1).first { it is ConfigState.Retrieved }

                            Then("the new config and geo info should be fetched and used") {
                                assertEquals("not", configManager.config?.buildId)
                            }
                        }
                    }
                }
            }
        }
}
