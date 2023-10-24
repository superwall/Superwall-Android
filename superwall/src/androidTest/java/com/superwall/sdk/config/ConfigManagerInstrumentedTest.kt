package com.superwall.sdk.config

import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.dependencies.DependencyContainer
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
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.StorageMock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test


class ConfigManagerUnderTest(
    private val storage: Storage,
    private val network: Network,
    private val paywallManager: PaywallManager,
    private val factory: Factory,
) : ConfigManager(
    storage = storage,
    network = network,
    paywallManager = paywallManager,
    factory = factory
) {

    suspend fun setConfig(config: Config) {
        configState.emit(Result.Success(ConfigState.Retrieved(config)))
    }
}

class ConfigManagerTests {

    @Test
    fun test_confirmAssignment() = runTest {
        // get context
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val experimentId = "abc"
        val variantId = "def"
        val variant = Experiment.Variant(
            id = variantId,
            type = Experiment.Variant.VariantType.TREATMENT,
            paywallId = "jkl"
        )
        val assignment = ConfirmableAssignment(experimentId = experimentId, variant = variant)
        val dependencyContainer = DependencyContainer(context, null, null)
        val network = NetworkMock(factory = dependencyContainer)
        val storage = StorageMock(context = context)
        val configManager = ConfigManager(
            options = null,
//            storeKitManager = dependencyContainer.storeKitManager,
            storage = storage,
            network = network,
            paywallManager = dependencyContainer.paywallManager,
            factory = dependencyContainer
        )
        configManager.confirmAssignment(assignment)

        delay(200)

        assertTrue(network.assignmentsConfirmed)
        assertEquals(storage.getConfirmedAssignments()[experimentId], variant)
        assertNull(configManager.unconfirmedAssignments[experimentId])
    }

    @Test
    fun test_loadAssignments_noConfig() = runTest {
        // get context
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val dependencyContainer = DependencyContainer(context, null, null)
        val network = NetworkMock(factory = dependencyContainer)
        val storage = StorageMock(context = context)
        val configManager = ConfigManager(
            options = null,
//            storeKitManager = dependencyContainer.storeKitManager,
            storage = storage,
            network = network,
            paywallManager = dependencyContainer.paywallManager,
            factory = dependencyContainer
        )

        val job = launch {
            configManager.getAssignments()
            ensureActive()
            // Make sure we never get here...
            assert(false)
        }

        delay(1000)

        job.cancel()

        assertTrue(storage.getConfirmedAssignments().isEmpty())
        assertTrue(configManager.unconfirmedAssignments.isEmpty())
    }

    @Test
    fun test_loadAssignments_noTriggers() = runTest {

        // get context
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val dependencyContainer = DependencyContainer(context, null, null)
        val network = NetworkMock(factory = dependencyContainer)
        val storage = StorageMock(context = context)
        val configManager = ConfigManagerUnderTest(
//            storeKitManager = dependencyContainer.storeKitManager,
            storage = storage,
            network = network,
            paywallManager = dependencyContainer.paywallManager,
            factory = dependencyContainer
        )
        configManager.setConfig(
            Config.stub().apply { this.triggers = emptySet() }
        )

        configManager.getAssignments()

        assertTrue(storage.getConfirmedAssignments().isEmpty())
        assertTrue(configManager.unconfirmedAssignments.isEmpty())
    }

    @Test
    fun test_loadAssignments_saveAssignmentsFromServer() = runTest {

        // get context
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val dependencyContainer = DependencyContainer(context, null, null)
        val network = NetworkMock(factory = dependencyContainer)
        val storage = StorageMock(context = context)
        val configManager = ConfigManagerUnderTest(
//            storeKitManager = dependencyContainer.storeKitManager,
            storage = storage,
            network = network,
            paywallManager = dependencyContainer.paywallManager,
            factory = dependencyContainer
        )

        val variantId = "variantId"
        val experimentId = "experimentId"

        val assignments: List<Assignment> = listOf(
            Assignment(experimentId = experimentId, variantId = variantId)
        )
        network.assignments = assignments.toMutableList()

        val variantOption = VariantOption.stub().apply { id = variantId }
        configManager.setConfig(
            Config.stub().apply {
                triggers = setOf(
                    Trigger.stub().apply {
                        rules = listOf(
                            TriggerRule.stub().apply {
                                this.experimentId = experimentId
                                this.variants = listOf(variantOption)
                            }
                        )
                    }
                )
            }
        )

        configManager.getAssignments()

        delay(1)

        assertEquals(storage.getConfirmedAssignments()[experimentId], variantOption.toVariant())
        assertTrue(configManager.unconfirmedAssignments.isEmpty())
    }
}
