package com.superwall.sdk.customer

import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.customer.CustomerInfo
import com.superwall.sdk.models.customer.SubscriptionTransaction
import com.superwall.sdk.storage.LatestCustomerInfo
import com.superwall.sdk.storage.LatestDeviceCustomerInfo
import com.superwall.sdk.storage.LatestWebCustomerInfo
import com.superwall.sdk.storage.Storage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.Date

class CustomerInfoManagerTest {
    private lateinit var storage: Storage
    private lateinit var customerInfoFlow: MutableStateFlow<CustomerInfo>
    private lateinit var manager: CustomerInfoManager
    private val testDispatcher = StandardTestDispatcher()
    private val ioScope = IOScope(testDispatcher)

    @Before
    fun setup() {
        storage = mockk(relaxed = true)
        customerInfoFlow = MutableStateFlow(CustomerInfo.empty())
        manager = CustomerInfoManager(storage, customerInfoFlow, ioScope)
    }

    @Test
    fun `updateMergedCustomerInfo merges device and web info`() =
        runTest(testDispatcher) {
            val deviceInfo =
                CustomerInfo(
                    subscriptions =
                        listOf(
                            SubscriptionTransaction(
                                transactionId = "device_tx",
                                productId = "product1",
                                purchaseDate = Date(1000),
                                willRenew = true,
                                isRevoked = false,
                                isInGracePeriod = false,
                                isInBillingRetryPeriod = false,
                                isActive = true,
                                expirationDate = Date(2000),
                            ),
                        ),
                    nonSubscriptions = emptyList(),
                    userId = "user123",
                    entitlements = emptyList(),
                    isBlank = false,
                )

            val webInfo =
                CustomerInfo(
                    subscriptions =
                        listOf(
                            SubscriptionTransaction(
                                transactionId = "web_tx",
                                productId = "product2",
                                purchaseDate = Date(1500),
                                isActive = true,
                                expirationDate = Date(2500),
                                willRenew = true,
                                isInGracePeriod = false,
                                isRevoked = false,
                                isInBillingRetryPeriod = false,
                            ),
                        ),
                    nonSubscriptions = emptyList(),
                    userId = "",
                    entitlements = emptyList(),
                    isBlank = false,
                )

            every { storage.read(LatestDeviceCustomerInfo) } returns deviceInfo
            every { storage.read(LatestWebCustomerInfo) } returns webInfo

            manager.updateMergedCustomerInfo()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { storage.write(LatestCustomerInfo, any()) }
            assert(customerInfoFlow.value.subscriptions.size == 2)
            assert(customerInfoFlow.value.userId == "user123")
        }

    @Test
    fun `updateMergedCustomerInfo uses device info when web is blank`() =
        runTest(testDispatcher) {
            val deviceInfo =
                CustomerInfo(
                    subscriptions =
                        listOf(
                            SubscriptionTransaction(
                                transactionId = "device_tx",
                                productId = "product1",
                                purchaseDate = Date(1000),
                                willRenew = true,
                                isRevoked = false,
                                isInGracePeriod = false,
                                isInBillingRetryPeriod = false,
                                isActive = true,
                                expirationDate = Date(2000),
                            ),
                        ),
                    nonSubscriptions = emptyList(),
                    userId = "user123",
                    entitlements = emptyList(),
                    isBlank = false,
                )

            every { storage.read(LatestDeviceCustomerInfo) } returns deviceInfo
            every { storage.read(LatestWebCustomerInfo) } returns null

            manager.updateMergedCustomerInfo()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { storage.write(LatestCustomerInfo, deviceInfo) }
            assert(customerInfoFlow.value == deviceInfo)
        }

    @Test
    fun `updateMergedCustomerInfo uses web info when device is blank`() =
        runTest(testDispatcher) {
            val webInfo =
                CustomerInfo(
                    subscriptions =
                        listOf(
                            SubscriptionTransaction(
                                transactionId = "web_tx",
                                productId = "product1",
                                purchaseDate = Date(1000),
                                willRenew = true,
                                isRevoked = false,
                                isInGracePeriod = false,
                                isInBillingRetryPeriod = false,
                                isActive = true,
                                expirationDate = Date(2000),
                            ),
                        ),
                    nonSubscriptions = emptyList(),
                    userId = "webuser",
                    entitlements = emptyList(),
                    isBlank = false,
                )

            every { storage.read(LatestDeviceCustomerInfo) } returns null
            every { storage.read(LatestWebCustomerInfo) } returns webInfo

            manager.updateMergedCustomerInfo()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { storage.write(LatestCustomerInfo, webInfo) }
            assert(customerInfoFlow.value == webInfo)
        }

    @Test
    fun `updateMergedCustomerInfo returns empty when both are blank`() =
        runTest(testDispatcher) {
            every { storage.read(LatestDeviceCustomerInfo) } returns null
            every { storage.read(LatestWebCustomerInfo) } returns null

            manager.updateMergedCustomerInfo()
            testDispatcher.scheduler.advanceUntilIdle()

            verify { storage.write(LatestCustomerInfo, match { it.isBlank }) }
            assert(customerInfoFlow.value.isBlank)
        }
}
