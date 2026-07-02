package com.superwall.sdk.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingConfig
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingResult
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Transaction.TransactionSource
import com.superwall.sdk.misc.AppLifecycleObserver
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.store.StoreManager
import com.superwall.sdk.store.testmode.TestStoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallProduct
import com.superwall.sdk.store.testmode.models.SuperwallProductPlatform
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StorefrontTest {
    private val okResult: BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()

    private val errorResult: BillingResult =
        BillingResult
            .newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.ERROR)
            .build()

    private lateinit var billingClient: BillingClient

    @Before
    fun setup() {
        billingClient =
            mockk(relaxed = true) {
                every { isReady } returns true
                every { startConnection(any()) } just Runs
                every { isFeatureSupported(any()) } returns okResult
            }
    }

    private fun makeWrapper(): GoogleBillingWrapper =
        GoogleBillingWrapper(
            context = mockk<Context>(relaxed = true),
            ioScope = IOScope(Dispatchers.Unconfined),
            appLifecycleObserver = AppLifecycleObserver(),
            factory = mockk(relaxed = true),
            createBillingClient = { billingClient },
        )

    @Test
    fun getStorefrontCountryCode_returnsCountryFromBillingConfig() =
        runTest {
            Given("a connected billing client that returns a billing config with a country code") {
                val config =
                    mockk<BillingConfig> {
                        every { countryCode } returns "US"
                    }
                every { billingClient.getBillingConfigAsync(any(), any()) } answers {
                    secondArg<BillingConfigResponseListener>().onBillingConfigResponse(okResult, config)
                }
                val wrapper = makeWrapper()

                When("the storefront country code is requested") {
                    val countryCode = wrapper.getStorefrontCountryCode()

                    Then("the billing config country is returned") {
                        assertEquals("US", countryCode)
                    }
                }
            }
        }

    @Test
    fun getStorefrontCountryCode_onConfigError_returnsNull() =
        runTest {
            Given("a connected billing client that fails to return a billing config") {
                every { billingClient.getBillingConfigAsync(any(), any()) } answers {
                    secondArg<BillingConfigResponseListener>().onBillingConfigResponse(errorResult, null)
                }
                val wrapper = makeWrapper()

                When("the storefront country code is requested") {
                    val countryCode = wrapper.getStorefrontCountryCode()

                    Then("null is returned") {
                        assertNull(countryCode)
                    }
                }
            }
        }

    @Test
    fun storeManager_loadsStorefrontCountryCodeOnce() =
        runTest {
            Given("a StoreManager whose billing returns a storefront country") {
                val billing =
                    mockk<Billing>(relaxed = true) {
                        coEvery { getStorefrontCountryCode() } returns "DE"
                    }
                val storeManager =
                    StoreManager(
                        purchaseController = mockk(relaxed = true),
                        billing = billing,
                        receiptManagerFactory = { mockk(relaxed = true) },
                        track = {},
                    )

                When("the storefront country code is loaded twice") {
                    storeManager.loadStorefrontCountryCode()
                    storeManager.loadStorefrontCountryCode()

                    Then("it is fetched from billing only once and cached") {
                        assertEquals("DE", storeManager.storefrontCountryCode)
                        coVerify(exactly = 1) { billing.getStorefrontCountryCode() }
                    }
                }
            }
        }

    private val someProduct =
        TestStoreProduct(
            SuperwallProduct(
                identifier = "test_product",
                platform = SuperwallProductPlatform.ANDROID,
            ),
        )

    @Test
    fun transactionCompleteEvent_includesPassedInStorefrontCountryCode() =
        runTest {
            Given("a transaction complete event with a storefront country code passed in") {
                val event =
                    InternalSuperwallEvent.Transaction(
                        state = InternalSuperwallEvent.Transaction.State.Complete(someProduct, null),
                        paywallInfo = PaywallInfo.empty(),
                        product = null,
                        model = null,
                        source = TransactionSource.INTERNAL,
                        isObserved = false,
                        demandScore = null,
                        demandTier = null,
                        userAttributes = emptyMap(),
                        storefrontCountryCode = "US",
                    )

                Then("the event params carry the storefront country code") {
                    assertEquals("US", event.getSuperwallParameters()["storefront_countryCode"])
                }
            }
        }

    @Test
    fun transactionCompleteEvent_withoutStorefront_fallsBackToEmpty() =
        runTest {
            Given("a transaction complete event with no storefront country code") {
                val event =
                    InternalSuperwallEvent.Transaction(
                        state = InternalSuperwallEvent.Transaction.State.Complete(someProduct, null),
                        paywallInfo = PaywallInfo.empty(),
                        product = null,
                        model = null,
                        source = TransactionSource.INTERNAL,
                        isObserved = false,
                        demandScore = null,
                        demandTier = null,
                        userAttributes = emptyMap(),
                    )

                Then("the event params fall back to an empty string") {
                    assertEquals("", event.getSuperwallParameters()["storefront_countryCode"])
                }
            }
        }

    @Test
    fun testStoreProduct_exposesBackendProvidedStorefront() {
        Given("a test mode product with a backend-provided storefront") {
            val product =
                TestStoreProduct(
                    SuperwallProduct(
                        identifier = "test_product",
                        platform = SuperwallProductPlatform.ANDROID,
                        storefront = "USA",
                    ),
                )

            Then("the product exposes it in its attributes") {
                assertEquals("USA", product.storeFrontCountryCode)
                assertEquals("USA", product.attributes["storeFrontCountryCode"])
            }
        }
    }

    @Test
    fun storeProduct_withoutStorefront_fallsBackToNa() {
        Given("a product without any storefront information") {
            val product =
                TestStoreProduct(
                    SuperwallProduct(
                        identifier = "test_product",
                        platform = SuperwallProductPlatform.ANDROID,
                    ),
                )

            Then("the attribute falls back to n/a") {
                assertNull(product.storeFrontCountryCode)
                assertEquals("n/a", product.attributes["storeFrontCountryCode"])
            }
        }
    }
}
