package com.superwall.sdk.config

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.misc.Result
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.ConfirmableAssignment
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.Trigger
import com.superwall.sdk.models.triggers.TriggerRule
import com.superwall.sdk.models.triggers.VariantOption
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.NetworkMock
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StorageMock
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

class ConfigManagerUnderTest(
    private val context: Context,
    private val storage: Storage,
    private val network: Network,
    private val paywallManager: PaywallManager,
    private val storeKitManager: StoreKitManager,
    private val factory: Factory,
    private val deviceHelper: DeviceHelper,
) : ConfigManager(
        context = context,
        storage = storage,
        network = network,
        paywallManager = paywallManager,
        storeKitManager = storeKitManager,
        factory = factory,
        deviceHelper = deviceHelper,
        options = SuperwallOptions(),
    ) {
    suspend fun setConfig(config: Config) {
        configState.emit(Result.Success(ConfigState.Retrieved(config)))
    }
}

@RunWith(AndroidJUnit4::class)
class ConfigManagerTests {
    private val helperFactory =
        object : DeviceHelper.Factory {
            override fun makeLocaleIdentifier() = Locale.US.toLanguageTag()

            override suspend fun makeIdentityInfo() = IdentityInfo("test", "test")
        }

    @Test
    fun test_confirmAssignment() =
        runTest {
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
            val assignment = ConfirmableAssignment(experimentId = experimentId, variant = variant)
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null)
            val network = NetworkMock(factory = dependencyContainer)
            val storage = StorageMock(context = context)
            val configManager =
                ConfigManager(
                    context = context,
                    options = SuperwallOptions(),
//            storeKitManager = dependencyContainer.storeKitManager,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeKitManager = dependencyContainer.storeKitManager,
                    factory = dependencyContainer,
                    deviceHelper = DeviceHelper(context, storage, network, helperFactory),
                )
            configManager.confirmAssignment(assignment)

            // Adding a delay because confirming assignments is on a queue
            delay(500)

            try {
                assertTrue(network.assignmentsConfirmed)
                assertEquals(storage.getConfirmedAssignments()[experimentId], variant)
                assertNull(configManager.unconfirmedAssignments[experimentId])
            } catch (e: Throwable) {
                throw e
            } finally {
                dependencyContainer.provideJavascriptEvaluator(context).teardown()
            }
        }

    @Test
    fun test_loadAssignments_noConfig() =
        runTest {
            // get context
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null)
            val evaluator = dependencyContainer.provideJavascriptEvaluator(context)
            val network = NetworkMock(factory = dependencyContainer)
            val storage = StorageMock(context = context)
            val configManager =
                ConfigManager(
                    context = context,
                    options = SuperwallOptions(),
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeKitManager = dependencyContainer.storeKitManager,
                    factory = dependencyContainer,
                    deviceHelper = DeviceHelper(context, storage, network, helperFactory),
                )

            val job =
                launch {
                    configManager.getAssignments()
                    ensureActive()
                    // Make sure we never get here...
                    assert(false)
                }

            delay(1000)

            job.cancel()

            try {
                assertTrue(storage.getConfirmedAssignments().isEmpty())
                assertTrue(configManager.unconfirmedAssignments.isEmpty())
            } catch (e: Throwable) {
                throw e
            } finally {
                evaluator.teardown()
            }
        }

    @Test
    fun test_loadAssignments_noTriggers() =
        runTest {
            // get context
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null)
            val network = NetworkMock(factory = dependencyContainer)
            val storage = StorageMock(context = context)
            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeKitManager = dependencyContainer.storeKitManager,
                    factory = dependencyContainer,
                    deviceHelper = DeviceHelper(context, storage, network, helperFactory),
                )
            configManager.setConfig(
                Config.stub().apply { this.triggers = emptySet() },
            )

            configManager.getAssignments()

            try {
                assertTrue(storage.getConfirmedAssignments().isEmpty())
                assertTrue(configManager.unconfirmedAssignments.isEmpty())
            } catch (e: Throwable) {
                throw e
            } finally {
                dependencyContainer.provideJavascriptEvaluator(context).teardown()
            }
        }

    @Test
    fun test_loadAssignments_saveAssignmentsFromServer() =
        runTest {
            // get context
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            val dependencyContainer =
                DependencyContainer(context, null, null, activityProvider = null)
            val network = NetworkMock(factory = dependencyContainer)
            val storage = StorageMock(context = context)
            val configManager =
                ConfigManagerUnderTest(
                    context = context,
                    storage = storage,
                    network = network,
                    paywallManager = dependencyContainer.paywallManager,
                    storeKitManager = dependencyContainer.storeKitManager,
                    factory = dependencyContainer,
                    deviceHelper = DeviceHelper(context, storage, network, helperFactory),
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

            configManager.getAssignments()

            delay(1)

            try {
                assertEquals(
                    storage.getConfirmedAssignments()[experimentId],
                    variantOption.toVariant(),
                )
                assertTrue(configManager.unconfirmedAssignments.isEmpty())
            } catch (e: Throwable) {
                throw e
            } finally {
                dependencyContainer.provideJavascriptEvaluator(context).teardown()
            }
        }
}
