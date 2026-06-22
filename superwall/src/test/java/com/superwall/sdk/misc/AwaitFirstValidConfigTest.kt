package com.superwall.sdk.misc

import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.models.config.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `awaitFirstValidConfig` deliberately filters out non-`Retrieved` states
 * (including `Failed`) so it cooperates with the retry model:
 * `Failed → Retrying → Retrieved` should resume waiters, not error them out.
 * These tests guard that contract.
 */
class AwaitFirstValidConfigTest {
    private val stubConfig = Config.stub()
    private val laterConfig = Config.stub()

    @Test
    fun `returns the Config when Retrieved is observed`() = runTest {
        val flow = flowOf(ConfigState.Retrieved(stubConfig))

        val result = flow.awaitFirstValidConfig()

        assertSame(stubConfig, result)
    }

    @Test
    fun `skips Failed and resumes once Retrieved arrives (supports retry model)`() = runTest {
        val state = MutableStateFlow<ConfigState>(ConfigState.Retrieving)

        val resultJob =
            backgroundScope.launch {
                val resumed = state.awaitFirstValidConfig()
                assertSame(laterConfig, resumed)
            }

        // Transient Failed (e.g. between retries) must not unblock the awaiter.
        state.value = ConfigState.Failed(RuntimeException("transient"))
        val earlyExitAfterFailed =
            withTimeoutOrNull(1.seconds) {
                resultJob.join()
            }
        assertNull("Failed must not resolve the awaiter", earlyExitAfterFailed)
        assertTrue("awaiter still active after Failed", resultJob.isActive)

        // Retry path lands on Retrieved — awaiter resumes with that config.
        state.value = ConfigState.Retrying
        state.value = ConfigState.Retrieved(laterConfig)
        resultJob.join()
        assertTrue("awaiter completed on Retrieved", resultJob.isCompleted)
    }

    @Test
    fun `skips intermediate non-terminal states and returns on Retrieved`() = runTest {
        val flow =
            flowOf(
                ConfigState.None,
                ConfigState.Retrieving,
                ConfigState.Retrying,
                ConfigState.Retrieved(stubConfig),
            )

        val result = flow.awaitFirstValidConfig()

        assertSame(stubConfig, result)
    }
}
