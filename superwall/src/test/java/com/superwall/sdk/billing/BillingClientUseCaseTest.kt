package com.superwall.sdk.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingClientUseCaseTest {
    private data class Params(
        override val appInBackground: Boolean = false,
    ) : UseCaseParams

    private class TestUseCase(
        params: UseCaseParams = Params(),
        onError: (BillingError) -> Unit,
    ) : BillingClientUseCase<Unit>(
            useCaseParams = params,
            onError = onError,
            executeRequestOnUIThread = { _, request -> request(null) },
        ) {
        var executeCount = 0

        override fun executeAsync() {
            executeCount++
        }

        override fun onOk(received: Unit) = Unit
    }

    private fun billingResult(code: Int): BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(code)
            .setDebugMessage("test error")
            .build()

    @Test
    fun exhaustedNetworkAndGenericErrorsRemainTransient() {
        listOf(
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.ERROR,
        ).forEach { responseCode ->
            var receivedError: BillingError? = null
            val useCase = TestUseCase(onError = { receivedError = it })

            repeat(4) {
                useCase.processResult(billingResult(responseCode), Unit)
            }

            assertEquals(3, useCase.executeCount)
            assertTrue(receivedError is BillingError.WithCode)
            assertEquals(responseCode, receivedError?.code)
        }
    }

    @Test
    fun exhaustedServiceUnavailableRemainsTransient() {
        var receivedError: BillingError? = null
        val useCase = TestUseCase(onError = { receivedError = it })

        repeat(6) {
            useCase.processResult(
                billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE),
                Unit,
            )
        }

        assertEquals(5, useCase.executeCount)
        assertTrue(receivedError is BillingError.WithCode)
        assertEquals(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, receivedError?.code)
    }

    @Test
    fun unavailableBillingAndUnsupportedFeaturesRemainPermanent() {
        listOf(
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        ).forEach { responseCode ->
            var receivedError: BillingError? = null
            val useCase = TestUseCase(onError = { receivedError = it })

            useCase.processResult(billingResult(responseCode), Unit)

            assertTrue(receivedError is BillingError.BillingNotAvailable)
            assertEquals(0, useCase.executeCount)
        }
    }
}
