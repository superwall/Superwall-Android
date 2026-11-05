package com.superwall.sdk.paywall.presentation.internal.operators

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.TrackingLogic
import com.superwall.sdk.analytics.internal.TrackingParameters
import com.superwall.sdk.analytics.internal.TrackingResult
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.paywall.presentation.internal.PresentationRequest
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class PublicPresentationOperatorsTest {
    @After
    fun tearDown() {
        runCatching { unmockkObject(TrackingLogic.Companion) }
        runCatching { unmockkStatic("com.superwall.sdk.analytics.internal.TrackingKt") }
    }

    @Test
    fun `logErrors tracks presentation request when status reason`() =
        runTest {
            Given("a paywall request and status reason error") {
                val superwall = mockk<com.superwall.sdk.Superwall>(relaxed = true)
                val dependencyContainer = mockk<com.superwall.sdk.dependencies.DependencyContainer>(relaxed = true)
                every { superwall.dependencyContainer } returns dependencyContainer

                mockkObject(TrackingLogic.Companion)
                every { TrackingLogic.checkNotSuperwallEvent(any()) } answers { }
                mockkStatic("com.superwall.sdk.analytics.internal.TrackingKt")
                coEvery { superwall.track(any()) } returns
                    Result.success(
                        TrackingResult(EventData.stub(), TrackingParameters.stub()),
                    )

                val request = createPresentationRequest()
                val error =
                    com.superwall.sdk.paywall.presentation.internal.PaywallPresentationRequestStatusReason
                        .PaywallAlreadyPresented()

                When("logErrors is invoked") {
                    superwall.logErrors(request, error)

                    Then("a presentation request event is tracked") {
                        coVerify { superwall.track(match { it is com.superwall.sdk.analytics.internal.trackable.Trackable }) }
                    }
                }
            }
        }

    @Test
    fun `logErrors skips tracking for non status errors`() =
        runTest {
            Given("a non status throwable") {
                val superwall = mockk<com.superwall.sdk.Superwall>(relaxed = true)

                mockkStatic("com.superwall.sdk.analytics.internal.TrackingKt")
                coEvery { superwall.track(any()) } returns
                    Result.success(
                        TrackingResult(EventData.stub(), TrackingParameters.stub()),
                    )

                When("logErrors runs with a regular error") {
                    superwall.logErrors(createPresentationRequest(), IllegalStateException("boom"))

                    Then("no tracking call is issued") {
                        coVerify(exactly = 0) { superwall.track(any()) }
                    }
                }
            }
        }

    private fun createPresentationRequest(): PresentationRequest =
        PresentationRequest(
            presentationInfo = PresentationInfo.ExplicitTrigger(EventData.stub()),
            flags =
                PresentationRequest.Flags(
                    isDebuggerLaunched = false,
                    entitlements = MutableStateFlow<SubscriptionStatus?>(null),
                    isPaywallPresented = false,
                    type = PresentationRequestType.Presentation,
                ),
        )
}
