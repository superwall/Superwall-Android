package com.superwall.sdk.store.testmode

import com.superwall.sdk.store.abstractions.product.ApiStoreProduct
import com.superwall.sdk.store.testmode.models.SuperwallProduct

/**
 * StoreProductType backing test-mode products. Shares its implementation with
 * [ApiStoreProduct] — both are built from Superwall /products endpoint data — but
 * remains a distinct type so test-mode products are distinguishable from custom
 * (store == CUSTOM) products.
 */
class TestStoreProduct(
    superwallProduct: SuperwallProduct,
) : ApiStoreProduct(superwallProduct)
