package com.superwall.sdk.store.products

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.SkuDetails
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

// Mock implementation of SkuDetails from Google Billing 4.0

val mockSku = """{
    "productId": "premium_subscription",
    "type": "subs",
    "price": "$9.99",
    "price_amount_micros": 9990000,
    "price_currency_code": "USD",
    "title": "Premium Subscription",
    "description": "Unlock all premium features with this subscription.",
    "subscriptionPeriod": "P1M",
    "freeTrialPeriod": "P7D",
    "introductoryPrice": "$4.99",
    "introductoryPriceCycles": 1,
    "introductoryPrice_period": "P1M"
}
"""

class MockSkuDetails(jsonDetails: String) : SkuDetails(jsonDetails) {

}

class ProductFetcherUnderTest(context: Context) : GooglePlayProductsFetcher(context = context) {

    // We're going to override the query async method to return a list of products
    // that we define in the test

    public var productIdsToReturn: Map<String, Result<RawStoreProduct>> = emptyMap()


    public var queryProductDetailsCalls: List<List<String>> = emptyList()

    override suspend fun queryProductDetails(productIds: List<String>): Map<String, Result<RawStoreProduct>> {
        queryProductDetailsCalls = queryProductDetailsCalls + listOf(productIds)
        delay(1000 + (Math.random() * 1000).toLong())

        // Filter productIdsToReturn, and add success if not found
        val result = productIds.map { productId ->
            val product = productIdsToReturn[productId]
            if (product != null) {
                productId to product
            } else {
                productId to Result.Success(
                    RawStoreProduct(
                        underlyingProductDetails = MockSkuDetails(mockSku)
                    )
                )
            }
        }.toMap()
        return result
    }

}

// TODO: https://linear.app/superwall/issue/SW-2368/[android]-fix-product-fetcher-tests
//@RunWith(AndroidJUnit4::class)
//class ProductFetcherInstrumentedTest {
//
//    @Test
//    fun test_fetch_products_without_connection() = runTest {
//        // get context
//        val context = InstrumentationRegistry.getInstrumentation().targetContext
//        val productFetcher: ProductFetcherUnderTest = ProductFetcherUnderTest(context)
//
//
//        val deffereds = listOf(
//            async { productFetcher.requestAndAwait(listOf("1", "2")) },
//            async { productFetcher.requestAndAwait(listOf("1", "2", "3")) }
//        )
//        deffereds.awaitAll()
//
//        print("!!! Defered resutls ${deffereds.map { it.getCompleted() }}")
//
//        println("!!! Calls: ${productFetcher.queryProductDetailsCalls}")
//        assert(productFetcher.queryProductDetailsCalls.size == 2)
//
//        // Check that the first call is for 1 and 2
//        assert(productFetcher.queryProductDetailsCalls[0].size == 2)
//        assert(productFetcher.queryProductDetailsCalls[0][0] == "1")
//        assert(productFetcher.queryProductDetailsCalls[0][1] == "2")
//
//        // Check that the second call is for 3
//        assert(productFetcher.queryProductDetailsCalls[1].size == 1)
//        assert(productFetcher.queryProductDetailsCalls[1][0] == "3")
//
//
//    }
//}