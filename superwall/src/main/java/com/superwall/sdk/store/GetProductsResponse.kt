package com.superwall.sdk.store

import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.models.product.ProductItem
import com.superwall.sdk.store.abstractions.product.StoreProduct

data class GetProductsResponse(
    val productsByFullId: Map<String, StoreProduct>,
    val productItems: List<ProductItem>,
    val paywall: Paywall? = null,
)
