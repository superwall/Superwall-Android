package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.config.models.ConfigState
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WaitForSubsStatusAndConfigTest {
    @After
    fun tearDown() {
        runCatching { unmockkStatic("com.superwall.sdk.analytics.internal.TrackingKt") }
    }

    @Test
    fun `waitForEntitlementsAndConfig returns when entitlements are set and config ready`() =
        runTest {
            Given("ready entitlements and retrieved config") {
                val superwall = mockk<com.superwall.sdk.Superwall>(relaxed = true)
                val dependencyContainer = mockk<DependencyContainer>(relaxed = true)
                every { superwall.dependencyContainer } returns dependencyContainer

                val configState = MutableStateFlow<ConfigState>(ConfigState.Retrieved(Config.stub()))
                every { dependencyContainer.configManager.configState } returns configState

                val identityManager = mockk<com.superwall.sdk.identity.IdentityManager>(relaxed = true)
                coEvery { identityManager.awaitLatestIdentity() } returns mockk(relaxed = true)
                every { dependencyContainer.identityManager } returns identityManager

                val request =
                    PresentationRequest(
                        presentationInfo = PresentationInfo.ExplicitTrigger(EventData.stub()),
                        flags =
                            PresentationRequest.Flags(
                                isDebuggerLaunched = false,
                                entitlements = MutableStateFlow<SubscriptionStatus?>(SubscriptionStatus.Inactive),
                                isPaywallPresented = false,
                                type = PresentationRequestType.Presentation,
                            ),
                    )

                When("waitForEntitlementsAndConfig executes") {
                    waitForEntitlementsAndConfig(request, paywallStatePublisher = null, dependencyContainer = dependencyContainer)

                    Then("it completes without emitting errors") { }
                }
            }
        }

    // -----------------------------------------------------------------------
    // configOrThrow — recursive retry logic
    // -----------------------------------------------------------------------

    @Test
    fun `configOrThrow returns immediately when state is already Retrieved`() =
        runTest {
            Given("config state is Retrieved") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Retrieved(Config.stub()))
                When("configOrThrow is called") {
                    state.configOrThrow()
                    Then("it returns without waiting") { }
                }
            }
        }

    @Test
    fun `configOrThrow throws the stored throwable when state is Failed`() =
        runTest {
            val cause = RuntimeException("bad config")
            Given("config state is Failed with a specific cause") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Failed(cause))
                When("configOrThrow is called") {
                    val thrown = assertFailsWith<RuntimeException> { state.configOrThrow() }
                    Then("it throws the throwable stored in ConfigState.Failed") {
                        assertEquals(cause.message, thrown.message)
                    }
                }
            }
        }

    @Test
    fun `configOrThrow retries and succeeds when state transitions to Retrieved after initial timeout`() =
        runTest {
            Given("config starts Retrieving and transitions to Retrieved at 1500ms") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
                backgroundScope.launch {
                    delay(1500.milliseconds)
                    state.value = ConfigState.Retrieved(Config.stub())
                }
                When("configOrThrow is called with retriesLeft=1") {
                    state.configOrThrow(retriesLeft = 1)
                    Then("it returns after the retry picks up the transition") { }
                }
            }
        }

    @Test
    fun `configOrThrow succeeds immediately when Retrying transitions to Retrieved within 1s`() =
        runTest {
            Given("config starts Retrying and transitions to Retrieved at 500ms") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Retrying)
                backgroundScope.launch {
                    delay(500.milliseconds)
                    state.value = ConfigState.Retrieved(Config.stub())
                }
                When("configOrThrow is called") {
                    state.configOrThrow(retriesLeft = 1)
                    Then("it returns without exhausting the retry") { }
                }
            }
        }

    @Test
    fun `configOrThrow throws TimeoutCancellationException when config never arrives`() =
        runTest {
            Given("config stays Retrieving indefinitely") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
                When("configOrThrow is called with retriesLeft=1") {
                    assertFailsWith<TimeoutCancellationException> {
                        state.configOrThrow(retriesLeft = 1)
                    }
                    Then("it throws after the total 6s window (1s + 5s)") { }
                }
            }
        }

    @Test
    fun `configOrThrow with retriesLeft=0 throws after 5s when config never arrives`() =
        runTest {
            Given("config stays Retrieving with no retries allowed") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
                When("configOrThrow is called with retriesLeft=0") {
                    assertFailsWith<TimeoutCancellationException> {
                        state.configOrThrow(retriesLeft = 0)
                    }
                    Then("it throws after the 5s final-attempt window") { }
                }
            }
        }

    @Test
    fun `configOrThrow propagates Failed throwable that arrives during the retry window`() =
        runTest {
            val cause = RuntimeException("failed mid-retry")
            Given("config starts Retrieving and transitions to Failed during the 5s retry window") {
                val state = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
                backgroundScope.launch {
                    delay(1500.milliseconds) // after the 1s initial timeout fires
                    state.value = ConfigState.Failed(cause)
                }
                When("configOrThrow is called with retriesLeft=1") {
                    val thrown = assertFailsWith<RuntimeException> {
                        state.configOrThrow(retriesLeft = 1)
                    }
                    Then("it throws the stored cause immediately, not a TimeoutCancellationException") {
                        assertEquals(cause.message, thrown.message)
                    }
                }
            }
        }

    @Test
    fun `configOrThrow treats None state the same as Retrieving`() =
        runTest {
            Given("config state is None and transitions to Retrieved at 500ms") {
                val state = MutableStateFlow<ConfigState>(ConfigState.None)
                backgroundScope.launch {
                    delay(500.milliseconds)
                    state.value = ConfigState.Retrieved(Config.stub())
                }
                When("configOrThrow is called") {
                    state.configOrThrow(retriesLeft = 1)
                    Then("it returns after the transition") { }
                }
            }
        }

    @Test
    fun `waitForEntitlementsAndConfig throws NoConfig and emits error when config times out`() =
        runTest {
            Given("entitlements ready but config stays Retrieving beyond the total timeout") {
                val dependencyContainer = mockk<DependencyContainer>(relaxed = true)
                every { dependencyContainer.ioScope() } returns com.superwall.sdk.misc.IOScope(coroutineContext)

                val configState = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
                every { dependencyContainer.configManager.configState } returns configState

                val identityManager = mockk<com.superwall.sdk.identity.IdentityManager>(relaxed = true)
                coEvery { identityManager.awaitLatestIdentity() } returns mockk(relaxed = true)
                every { dependencyContainer.identityManager } returns identityManager

                mockkStatic("com.superwall.sdk.analytics.internal.TrackingKt")

                val publisher = MutableSharedFlow<PaywallState>(replay = 1)
                val request =
                    PresentationRequest(
                        presentationInfo = PresentationInfo.ExplicitTrigger(EventData.stub()),
                        flags =
                            PresentationRequest.Flags(
                                isDebuggerLaunched = false,
                                entitlements = MutableStateFlow<SubscriptionStatus?>(SubscriptionStatus.Inactive),
                                isPaywallPresented = false,
                                type = PresentationRequestType.Presentation,
                            ),
                    )

                When("waitForEntitlementsAndConfig executes") {
                    assertFailsWith<PaywallPresentationRequestStatusReason.NoConfig> {
                        waitForEntitlementsAndConfig(request, paywallStatePublisher = publisher, dependencyContainer = dependencyContainer)
                    }

                    Then("a presentation error is emitted to the publisher") {
                        val emitted = publisher.replayCache.lastOrNull()
                        assert(emitted is PaywallState.PresentationError)
                    }
                }
            }
        }

    @Test
    fun `waitForEntitlementsAndConfig emits error and throws when config is missing`() =
        runTest {
            Given("entitlements ready but config failed") {
                val superwall = mockk<com.superwall.sdk.Superwall>(relaxed = true)
                val dependencyContainer = mockk<DependencyContainer>(relaxed = true)
                every { superwall.dependencyContainer } returns dependencyContainer
                every { superwall.ioScope } returns
                    com.superwall.sdk.misc
                        .IOScope(coroutineContext)

                val configState = MutableStateFlow<ConfigState>(ConfigState.Failed(IllegalStateException("no config")))
                every { dependencyContainer.configManager.configState } returns configState

                val identityManager = mockk<com.superwall.sdk.identity.IdentityManager>(relaxed = true)
                coEvery { identityManager.awaitLatestIdentity() } returns mockk(relaxed = true)
                every { dependencyContainer.identityManager } returns identityManager

                mockkStatic("com.superwall.sdk.analytics.internal.TrackingKt")
                coEvery { superwall.track(any()) } returns Result.success(mockk())

                val publisher = MutableSharedFlow<PaywallState>(replay = 1)
                val request =
                    PresentationRequest(
                        presentationInfo = PresentationInfo.ExplicitTrigger(EventData.stub()),
                        flags =
                            PresentationRequest.Flags(
                                isDebuggerLaunched = false,
                                entitlements = MutableStateFlow<SubscriptionStatus?>(SubscriptionStatus.Inactive),
                                isPaywallPresented = false,
                                type = PresentationRequestType.Presentation,
                            ),
                    )

                When("waitForEntitlementsAndConfig executes") {
                    kotlin.test.assertFailsWith<PaywallPresentationRequestStatusReason.NoConfig> {
                        waitForEntitlementsAndConfig(request, paywallStatePublisher = publisher, dependencyContainer = dependencyContainer)
                    }

                    Then("a presentation error is emitted") {
                        val emitted = publisher.replayCache.lastOrNull()
                        assert(emitted is PaywallState.PresentationError)
                    }
                }
            }
        }
}
