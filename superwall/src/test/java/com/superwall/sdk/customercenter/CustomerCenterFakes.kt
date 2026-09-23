package com.superwall.sdk.customercenter

import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.customer.CustomerInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.Date
import java.util.Locale

internal class FakeCustomerInfo(
    var info: CustomerInfo,
) : CustomerCenterCustomerInfoProviding {
    var refreshCount = 0
    override val updates = MutableSharedFlow<CustomerInfo>()

    override suspend fun fetchCustomerInfo() = info

    override suspend fun refreshPurchases(): CustomerInfo {
        refreshCount += 1
        return info
    }
}

internal class FakeProducts(
    val products: Map<String, ProductDisplayInfo> = emptyMap(),
    val catalogue: Map<String, ProductDisplayInfo> = emptyMap(),
) : CustomerCenterProductsProviding {
    var catalogueRequests = mutableListOf<Set<String>>()

    override suspend fun products(ids: Set<String>) = products.filterKeys { it in ids }

    override suspend fun catalogueProducts(ids: Set<String>): Map<String, ProductDisplayInfo> {
        catalogueRequests.add(ids)
        return catalogue.filterKeys { it in ids }
    }
}

internal class FakeRestorer(
    var result: RestorationResult = RestorationResult.Restored(),
) : CustomerCenterRestoring {
    var count = 0

    override suspend fun restorePurchases(): RestorationResult {
        count += 1
        return result
    }
}

internal class FakeOpener(
    var opens: Boolean = true,
) : CustomerCenterUrlOpening {
    val opened = mutableListOf<Pair<String, Boolean>>()
    override val canOpenUrls = true

    override fun open(
        url: String,
        inApp: Boolean,
    ): Boolean {
        opened.add(url to inApp)
        return opens
    }
}

internal class FakeTracker : CustomerCenterEventTracking {
    val events = mutableListOf<TrackableSuperwallEvent>()

    override suspend fun track(event: TrackableSuperwallEvent) {
        events.add(event)
    }
}

internal class FakeEnvironment(
    override val appVersion: String = "1.0.0",
    override val webManagementUrl: String? = null,
) : CustomerCenterEnvironmentProviding {
    override val osVersion = "14"
    override val deviceModel = "Pixel"
    override val sdkVersion = "2.9.0"
    override val userId = "user_1"
    override val isSandbox = false
    override val packageName = "com.example.app"
    override val originalDownloadDate: Date? = null
    override val locale: Locale = Locale.US
}
