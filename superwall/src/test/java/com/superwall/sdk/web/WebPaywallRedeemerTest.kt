package com.superwall.sdk.web

import android.content.Context
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.entitlements.TransactionReceipt
import com.superwall.sdk.models.entitlements.WebEntitlements
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.ErrorInfo
import com.superwall.sdk.models.internal.PurchaserInfo
import com.superwall.sdk.models.internal.RedemptionInfo
import com.superwall.sdk.models.internal.RedemptionOwnership
import com.superwall.sdk.models.internal.RedemptionOwnershipType
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.models.internal.StoreIdentifiers
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.models.product.StripeProductType
import com.superwall.sdk.models.transactions.AbandonedCheckout
import com.superwall.sdk.models.transactions.CheckoutStatus
import com.superwall.sdk.models.transactions.CheckoutStatusResponse
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.paywall.presentation.PaywallInfo
import com.superwall.sdk.paywall.view.webview.PaywallMessage
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storable
import com.superwall.sdk.storage.Storage
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

class WebPaywallRedeemerTest {
    private val context: Context = mockk()
    private val storage: Storage =
        mockk {
            every { read(LatestRedemptionResponse) } returns null
            every { write(LatestRedemptionResponse, any()) } just Runs
        }

    private var maxAge: () -> Long = { 1L }
    private var mutableEntitlements = mutableSetOf<Entitlement>()
    private var webEntitlement = Entitlement("web_entitlement")
    private var normalEntitlement = Entitlement("normalEntitlement")

    private val deepLinkReferrer: CheckForReferral = mockk()
    private val testDispatcher = StandardTestDispatcher()
    private val testScheduler = testDispatcher.scheduler

    private val setEntitlementStatus: (List<Entitlement>) -> Unit = {
        mutableEntitlements += it.toSet()
    }
    private var getAllEntitlements: () -> Set<Entitlement> = {
        mutableEntitlements
    }

    private var isPaywallVisible = { false }
    private var showRestoreDialogAndDismiss = {}
    private var currentPaywallEntitlements = {
        setOf<Entitlement>()
    }
    private val setSubscriptionStatus: (SubscriptionStatus) -> Unit = { it: SubscriptionStatus ->
        when (it) {
            is SubscriptionStatus.Active -> mutableEntitlements.addAll(it.entitlements)
            else -> mutableEntitlements.clear()
        }
    }
    private var getActiveDeviceEntitlements = {
        setOf<Entitlement>()
    }

    @Before
    fun setup() {
        mutableEntitlements = mutableSetOf()
        coEvery {
            network.webEntitlementsByUserId(any(), any())
        } returns Either.Success(WebEntitlements(listOf(webEntitlement)))
    }

    private val onRedemptionResult: (RedemptionResult) -> Unit = mockk(relaxed = true)
    private val getUserId: () -> UserId = { UserId("test_user") }
    private val getDeviceId: () -> DeviceVendorId = { DeviceVendorId(VendorId("test_vendor")) }
    private val getAlias: () -> String = { "test_alias" }
    private val setActiveWebEntitlements: (Set<Entitlement>) -> Unit = {}
    private val track: (Trackable) -> Unit = {}
    private lateinit var redeemer: WebPaywallRedeemer
    private val network: Network = mockk {}

    @Test
    fun `test successful redemption flow`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with valid redemption codes") {
                val code = "test_code"
                mutableEntitlements = mutableSetOf(normalEntitlement)
                val response =
                    WebRedemptionResponse(
                        codes =
                            listOf(
                                RedemptionResult.Success(
                                    code = code,
                                    redemptionInfo =
                                        RedemptionInfo(
                                            ownership = RedemptionOwnership.AppUser(appUserId = getUserId().value),
                                            purchaserInfo =
                                                PurchaserInfo(
                                                    getUserId().value,
                                                    "email",
                                                    StoreIdentifiers.Stripe(
                                                        stripeCustomerId = "123",
                                                        emptyList(),
                                                    ),
                                                ),
                                            entitlements = listOf(webEntitlement),
                                        ),
                                ),
                            ),
                        entitlements = listOf(webEntitlement),
                    )
                coEvery {
                    network.webEntitlementsByUserId(any(), any())
                } returns Either.Success(WebEntitlements(listOf(webEntitlement)))

                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(code)
                coEvery {
                    network.redeemToken(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns Either.Success(response)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for referral") {
                    redeemer.checkForRefferal()

                    Then("it should redeem the codes and set entitlement status") {
                        verify(exactly = 1) {
                            storage.write(LatestRedemptionResponse, response)
                        }
                        println(mutableEntitlements)
                        assert(mutableEntitlements == setOf(webEntitlement, normalEntitlement))
                    }
                }
            }
        }

    @Test
    fun `test failed referral check`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with failing referral check") {
                val exception = Exception("Referral check failed")
                coEvery { deepLinkReferrer.checkForReferral() } returns Result.failure(exception)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for referral") {
                    redeemer.checkForRefferal()

                    Then("it should not call redeem") {
                        coVerify(exactly = 0) {
                            network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                        }
                    }
                }
            }
        }

    @Test
    fun `test failed token redemption due to unknown error`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with failing token redemption") {
                val codes = "code1"
                val exception = Exception("Token redemption failed")
                // User has a normal entitlement
                mutableEntitlements = mutableSetOf(normalEntitlement)
                println(mutableEntitlements)
                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(codes)
                coEvery {
                    network.redeemToken(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns Either.Failure(NetworkError.Unknown(exception))
                coEvery {
                    network.webEntitlementsByUserId(any(), any())
                } returns Either.Success(WebEntitlements(listOf()))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(backgroundScope.coroutineContext),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = onRedemptionResult,
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for referral") {
                    // This is commented out as on init we already do the check
                    // redeemer.checkForRefferal()
                    async(Dispatchers.Default) {
                        delay(1.seconds)
                    }.await()
                    Then("it should not set entitlement status") {
                        assert(mutableEntitlements == setOf(normalEntitlement))
                        verify(exactly = 1) {
                            onRedemptionResult(
                                RedemptionResult.Error(
                                    code = codes,
                                    error =
                                        ErrorInfo(
                                            exception.localizedMessage ?: exception.message ?: "",
                                        ),
                                ),
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `test checkForWebEntitlements with successful responses`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with successful web entitlements responses") {
                val userEntitlements = listOf(Entitlement("user_entitlement"))

                coEvery {
                    network.webEntitlementsByUserId(any(), any())
                } returns Either.Success(WebEntitlements(userEntitlements))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for web entitlements") {
                    val result =
                        redeemer.checkForWebEntitlements(
                            getUserId(),
                            getDeviceId(),
                        )

                    Then("it should combine both entitlements lists") {
                        assert(result is Either.Success)
                        val entitlements = (result as Either.Success).value
                        assert(entitlements.containsAll(userEntitlements))
                    }
                }
            }
        }

    @Test
    fun `test checkForWebEntitlements with failed responses`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with failed web entitlements responses") {
                coEvery {
                    network.webEntitlementsByUserId(any(), any())
                } returns Either.Failure(NetworkError.Unknown(Error("User entitlements failed")))

                coEvery {
                    network.webEntitlementsByDeviceID(any())
                } returns Either.Failure(NetworkError.Unknown(Error("Device entitlements failed")))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for web entitlements") {
                    val result =
                        redeemer.checkForWebEntitlements(
                            UserId("test_user"),
                            DeviceVendorId(VendorId("test_device")),
                        )

                    Then("it should return an empty list") {
                        assert(result is Either.Success)
                        val entitlements = (result as Either.Success).value
                        assert(entitlements.isEmpty())
                    }
                }
            }
        }

    @Test
    fun `test checkForWebEntitlements with partially successful responses - user success`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with only user entitlements succeeding") {
                val userEntitlements =
                    setOf(Entitlement("user_entitlement"))

                coEvery {
                    network.webEntitlementsByUserId(UserId("test_user"), any())
                } returns Either.Success(WebEntitlements(userEntitlements.toList()))

                coEvery {
                    network.webEntitlementsByDeviceID(any())
                } returns Either.Failure(NetworkError.Unknown(Error("Device entitlements failed")))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for web entitlements") {
                    val result =
                        redeemer.checkForWebEntitlements(
                            UserId("test_user"),
                            DeviceVendorId(VendorId("test_device")),
                        )

                    Then("it should return only user entitlements") {
                        assert(result is Either.Success)
                        val entitlements = (result as Either.Success).value
                        assert(entitlements.first() == userEntitlements.first())
                    }
                }
            }
        }

    @Test
    fun `test checkForWebEntitlements with partially successful responses - device success`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with only device entitlements succeeding") {
                coEvery {
                    deepLinkReferrer.checkForReferral()
                } returns Result.success("code")

                coEvery {
                    network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                } returns Either.Failure(NetworkError.Unknown(Error("Token redemption failed")))

                coEvery {
                    network.webEntitlementsByUserId(any(), any())
                } returns Either.Success(WebEntitlements(listOf(webEntitlement)))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for web entitlements") {
                    val result =
                        redeemer.checkForWebEntitlements(
                            UserId("test_user"),
                            DeviceVendorId(VendorId("test_device")),
                        )

                    Then("it should return only device entitlements") {
                        assert(result is Either.Success)
                        val entitlements = (result as Either.Success).value
                        assert(entitlements == setOf(webEntitlement))
                    }
                }
            }
        }

    @Test
    fun `test checkForWebEntitlements with duplicate entitlements`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with overlapping entitlements from user and device") {
                val commonEntitlement = Entitlement("common_entitlement")
                val userEntitlements = listOf(commonEntitlement, Entitlement("user_specific"))

                coEvery {
                    network.webEntitlementsByUserId(any(), any())
                } returns Either.Success(WebEntitlements(userEntitlements))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for web entitlements") {
                    val result =
                        redeemer.checkForWebEntitlements(
                            UserId("test_user"),
                            DeviceVendorId(VendorId("test_device")),
                        )

                    Then("it should return combined entitlements without duplicates") {
                        assert(result is Either.Success)
                        val entitlements = (result as Either.Success).value
                        assert(entitlements.size == 2) // Should only have 3 unique entitlements
                        assert(entitlements.count { it == commonEntitlement } == 1) // Should only have one copy of the common entitlement
                    }
                }
            }
        }

    @Test
    fun clean_up_old_redemptions() {
        val userCode = "code1"
        val deviceCode = "code2"
        val userId = "test_user"
        Given("We have existing user redemptions") {
            val response =
                WebRedemptionResponse(
                    codes =
                        listOf(
                            RedemptionResult.Success(
                                code = userCode,
                                redemptionInfo =
                                    RedemptionInfo(
                                        ownership = RedemptionOwnership.AppUser(appUserId = userId),
                                        purchaserInfo =
                                            PurchaserInfo(
                                                userId,
                                                email = null,
                                                storeIdentifiers =
                                                    StoreIdentifiers.Stripe(
                                                        "123",
                                                        emptyList(),
                                                    ),
                                            ),
                                        entitlements = listOf(webEntitlement),
                                    ),
                            ),
                            RedemptionResult.Success(
                                code = deviceCode,
                                redemptionInfo =
                                    RedemptionInfo(
                                        ownership = RedemptionOwnership.Device(deviceId = "deviceId"),
                                        purchaserInfo =
                                            PurchaserInfo(
                                                userId,
                                                email = null,
                                                storeIdentifiers =
                                                    StoreIdentifiers.Stripe(
                                                        "123",
                                                        emptyList(),
                                                    ),
                                            ),
                                        entitlements = listOf(webEntitlement),
                                    ),
                            ),
                        ),
                    entitlements = listOf(webEntitlement),
                )
            val storage =
                object : Storage {
                    var saved: Any? = null

                    override fun <T> read(storable: Storable<T>): T? = saved as T?

                    override fun <T : Any> write(
                        storable: Storable<T>,
                        data: T,
                    ) {
                        saved = data as Any?
                    }

                    override fun clean() {
                    }
                }
            redeemer =
                WebPaywallRedeemer(
                    context,
                    IOScope(testDispatcher),
                    deepLinkReferrer,
                    network,
                    storage,
                    willRedeemLink = {},
                    didRedeemLink = {},
                    maxAge,
                    getActiveDeviceEntitlements,
                    getUserId,
                    getDeviceId,
                    getAlias,
                    track,
                    setSubscriptionStatus,
                    isPaywallVisible,
                    showRestoreDialogAndDismiss,
                    currentPaywallEntitlements,
                    getPaywallInfo = { PaywallInfo.empty() },
                    trackRestorationFailed = {},
                    isWebToAppEnabled = { true },
                    receipts = { listOf(TransactionReceipt("mock")) },
                    getExternalAccountId = { "" },
                )

            storage.write(LatestRedemptionResponse, response)
            When("We call clean") {
                redeemer.clear(RedemptionOwnershipType.AppUser)
                Then("It should remove the old redemptions") {
                    val saved = storage.saved as WebRedemptionResponse
                    println(saved.codes)
                    assert(saved.codes.size == 1)
                }
            }
        }
    }

    @Test
    fun `test attribution props are passed to redeemToken`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with attribution props") {
                val code = "test_code"
                val attributionProps =
                    mapOf(
                        "campaign" to "summer_sale",
                        "source" to "facebook",
                        "user_id" to 12345,
                    )
                val expectedJsonProps =
                    mapOf<String, JsonElement>(
                        "campaign" to JsonPrimitive("summer_sale"),
                        "source" to JsonPrimitive("facebook"),
                        "user_id" to JsonPrimitive(12345),
                    )

                mutableEntitlements = mutableSetOf(normalEntitlement)
                val response =
                    WebRedemptionResponse(
                        codes =
                            listOf(
                                RedemptionResult.Success(
                                    code = code,
                                    redemptionInfo =
                                        RedemptionInfo(
                                            ownership = RedemptionOwnership.AppUser(appUserId = "test_user"),
                                            purchaserInfo =
                                                PurchaserInfo(
                                                    "test_user",
                                                    "test@example.com",
                                                    StoreIdentifiers.Stripe(
                                                        stripeCustomerId = "123",
                                                        emptyList(),
                                                    ),
                                                ),
                                            entitlements = listOf(webEntitlement),
                                        ),
                                ),
                            ),
                        entitlements = listOf(webEntitlement),
                    )

                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(code)
                coEvery {
                    network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                } returns Either.Success(response)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { attributionProps },
                    )

                When("checking for referral with attribution props") {
                    redeemer.checkForRefferal()

                    Then("it should call redeemToken with converted attribution props") {
                        coVerify(exactly = 1) {
                            network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                        }
                    }
                }
            }
        }

    @Test
    fun `test orderId is included in TransactionReceipt`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with TransactionReceipts containing orderId") {
                val code = "test_code"
                val expectedOrderId = "test_order_123"
                val expectedPurchaseToken = "test_purchase_token"
                val expectedReceipts =
                    listOf(
                        TransactionReceipt(expectedPurchaseToken, expectedOrderId),
                    )

                mutableEntitlements = mutableSetOf(normalEntitlement)
                val response =
                    WebRedemptionResponse(
                        codes =
                            listOf(
                                RedemptionResult.Success(
                                    code = code,
                                    redemptionInfo =
                                        RedemptionInfo(
                                            ownership = RedemptionOwnership.AppUser(appUserId = "test_user"),
                                            purchaserInfo =
                                                PurchaserInfo(
                                                    "test_user",
                                                    "test@example.com",
                                                    StoreIdentifiers.Stripe(
                                                        stripeCustomerId = "123",
                                                        emptyList(),
                                                    ),
                                                ),
                                            entitlements = listOf(webEntitlement),
                                        ),
                                ),
                            ),
                        entitlements = listOf(webEntitlement),
                    )

                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(code)
                coEvery {
                    network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                } returns Either.Success(response)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { expectedReceipts },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("checking for referral") {
                    redeemer.checkForRefferal()

                    Then("it should call redeemToken with receipts containing orderId") {
                        coVerify(exactly = 1) {
                            network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                        }
                        // Verify the receipt contains both purchaseToken and orderId
                        assert(expectedReceipts[0].purchaseToken == expectedPurchaseToken)
                        assert(expectedReceipts[0].orderId == expectedOrderId)
                    }
                }
            }
        }

    @Test
    fun `test empty attribution props are not passed to redeemToken`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with empty attribution props") {
                val code = "test_code"
                val emptyAttributionProps = emptyMap<String, Any>()

                mutableEntitlements = mutableSetOf(normalEntitlement)
                val response =
                    WebRedemptionResponse(
                        codes =
                            listOf(
                                RedemptionResult.Success(
                                    code = code,
                                    redemptionInfo =
                                        RedemptionInfo(
                                            ownership = RedemptionOwnership.AppUser(appUserId = "test_user"),
                                            purchaserInfo =
                                                PurchaserInfo(
                                                    "test_user",
                                                    "test@example.com",
                                                    StoreIdentifiers.Stripe(
                                                        stripeCustomerId = "123",
                                                        emptyList(),
                                                    ),
                                                ),
                                            entitlements = listOf(webEntitlement),
                                        ),
                                ),
                            ),
                        entitlements = listOf(webEntitlement),
                    )

                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(code)
                coEvery {
                    network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                } returns Either.Success(response)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyAttributionProps },
                    )

                When("checking for referral with empty attribution props") {
                    redeemer.checkForRefferal()

                    Then("it should call redeemToken with null attribution props") {
                        coVerify(exactly = 1) {
                            network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                        }
                    }
                }
            }
        }

    @Test
    fun `test complex attribution props conversion to JsonElement`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with complex attribution props") {
                val code = "test_code"
                val complexAttributionProps =
                    mapOf(
                        "string_value" to "test_string",
                        "int_value" to 42,
                        "double_value" to 3.14,
                        "boolean_value" to true,
                        "nested_map" to mapOf("inner_key" to "inner_value"),
                        "list_value" to listOf("item1", "item2", 123),
                    )

                // Expected JSON conversion
                val expectedJsonProps =
                    mapOf<String, JsonElement>(
                        "string_value" to JsonPrimitive("test_string"),
                        "int_value" to JsonPrimitive(42),
                        "double_value" to JsonPrimitive(3.14),
                        "boolean_value" to JsonPrimitive(true),
                        "nested_map" to
                            kotlinx.serialization.json.buildJsonObject {
                                put("inner_key", JsonPrimitive("inner_value"))
                            },
                        "list_value" to
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("item1"),
                                    JsonPrimitive("item2"),
                                    JsonPrimitive(123),
                                ),
                            ),
                    )

                mutableEntitlements = mutableSetOf(normalEntitlement)
                val response =
                    WebRedemptionResponse(
                        codes =
                            listOf(
                                RedemptionResult.Success(
                                    code = code,
                                    redemptionInfo =
                                        RedemptionInfo(
                                            ownership = RedemptionOwnership.AppUser(appUserId = "test_user"),
                                            purchaserInfo =
                                                PurchaserInfo(
                                                    "test_user",
                                                    "test@example.com",
                                                    StoreIdentifiers.Stripe(
                                                        stripeCustomerId = "123",
                                                        emptyList(),
                                                    ),
                                                ),
                                            entitlements = listOf(webEntitlement),
                                        ),
                                ),
                            ),
                        entitlements = listOf(webEntitlement),
                    )

                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(code)
                coEvery {
                    network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                } returns Either.Success(response)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { complexAttributionProps },
                    )

                When("checking for referral with complex attribution props") {
                    redeemer.checkForRefferal()

                    Then("it should successfully convert and pass all attribution props types") {
                        coVerify(exactly = 1) {
                            network.redeemToken(any(), any(), any(), any(), any(), any(), any())
                        }
                        // The conversion should not throw an exception and should handle all data types
                    }
                }
            }
        }

    @Test
    fun `test startCheckoutSession with pending status retries until completion`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with checkout session that transitions from pending to completed") {
                val checkoutId = "checkout_123"
                val redemptionCodes = listOf("code1", "code2")
                val stripeProduct =
                    StripeProductType(
                        id = "test_product",
                        price = BigDecimal("9.99"),
                        localizedPrice = "$9.99",
                        currencyCode = "USD",
                        currencySymbol = "$",
                        priceLocale = StripeProductType.PriceLocale("", "", "", ""),
                        stripeSubscriptionPeriod = null,
                        subscriptionIntroOffer = null,
                        entitlements = emptyList(),
                    )

                var callCount = 0
                coEvery { network.checkoutStatus(checkoutId) } answers {
                    callCount++
                    if (callCount < 3) {
                        Either.Success(CheckoutStatusResponse(CheckoutStatus.Pending))
                    } else {
                        Either.Success(
                            CheckoutStatusResponse(
                                CheckoutStatus.Completed(
                                    redemptionCodes,
                                    stripeProduct,
                                ),
                            ),
                        )
                    }
                }

                val messageCallback =
                    mockk<(PaywallMessage, StripeProductType, List<String>) -> Unit>(relaxed = true)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("starting checkout session") {
                    redeemer.startCheckoutSession(checkoutId, messageCallback)
                    testScheduler.advanceUntilIdle() // Let all coroutines complete

                    Then("it should retry until completion and call callback") {
                        coVerify(atLeast = 3) { network.checkoutStatus(checkoutId) }
                        verify {
                            messageCallback(
                                PaywallMessage.TransactionComplete,
                                stripeProduct,
                                redemptionCodes,
                            )
                        }
                        assert(!redeemer.isCheckoutInProgress)
                    }
                }
            }
        }

    @Test
    fun `test startCheckoutSession with abandoned status tracks abandonment`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with checkout session that gets abandoned") {
                val checkoutId = "checkout_456"
                val stripeProduct =
                    StripeProductType(
                        id = "test_product",
                        price = BigDecimal("9.99"),
                        localizedPrice = "$9.99",
                        currencyCode = "USD",
                        currencySymbol = "$",
                        priceLocale = StripeProductType.PriceLocale("", "", "", ""),
                        stripeSubscriptionPeriod = null,
                        subscriptionIntroOffer = null,
                        entitlements = emptyList(),
                    )
                val abandonedCheckout =
                    AbandonedCheckout(
                        paywallId = "paywall_123",
                        variantId = "variant_456",
                        presentedByEventName = "register",
                        stripeProduct = stripeProduct,
                    )

                coEvery { network.checkoutStatus(checkoutId) } returns
                    Either.Success(
                        CheckoutStatusResponse(
                            CheckoutStatus.Abandoned(
                                abandonedCheckout,
                            ),
                        ),
                    )

                val messageCallback =
                    mockk<(PaywallMessage, StripeProductType, List<String>) -> Unit>(relaxed = true)
                val mockTrack: (Trackable) -> Unit = mockk(relaxed = true)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        mockTrack,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("starting checkout session that gets abandoned") {
                    redeemer.startCheckoutSession(checkoutId, messageCallback)
                    testScheduler.advanceUntilIdle() // Let all coroutines complete

                    Then("it should track abandonment and clear checkout state") {
                        coVerify(exactly = 1) { network.checkoutStatus(checkoutId) }
                        verify { mockTrack(any()) }
                        verify(exactly = 0) {
                            messageCallback(
                                PaywallMessage.TransactionComplete,
                                stripeProduct,
                                emptyList(),
                            )
                        }
                        assert(!redeemer.isCheckoutInProgress)
                    }
                }
            }
        }

    @Test
    fun `test startCheckoutSession with network error retries and eventually fails`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with failing network requests") {
                val checkoutId = "checkout_789"
                coEvery { network.checkoutStatus(checkoutId) } returns
                    Either.Failure(NetworkError.Unknown(Exception("Network error")))

                val messageCallback =
                    mockk<(PaywallMessage, StripeProductType, List<String>) -> Unit>(relaxed = true)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("starting checkout session with network errors") {
                    redeemer.startCheckoutSession(checkoutId, messageCallback)
                    testScheduler.advanceUntilIdle() // Let all coroutines complete

                    Then("it should retry 6 times and eventually clear checkout state") {
                        coVerify(exactly = 7) { network.checkoutStatus(checkoutId) }
                        verify(exactly = 0) {
                            messageCallback(
                                PaywallMessage.TransactionComplete,
                                any(),
                                any(),
                            )
                        }
                        assert(!redeemer.isCheckoutInProgress)
                    }
                }
            }
        }

    @Test
    fun `test startCheckoutSession state management - isCheckoutInProgress`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with checkout session") {
                val checkoutId = "checkout_state_test"
                val stripeProduct =
                    StripeProductType(
                        id = "state_product",
                        price = BigDecimal("5.99"),
                        localizedPrice = "$5.99",
                        currencyCode = "USD",
                        currencySymbol = "$",
                        priceLocale = StripeProductType.PriceLocale("", "", "", ""),
                        stripeSubscriptionPeriod = null,
                        subscriptionIntroOffer = null,
                        entitlements = emptyList(),
                    )

                coEvery { network.checkoutStatus(checkoutId) } returns
                    Either.Success(
                        CheckoutStatusResponse(
                            CheckoutStatus.Completed(
                                listOf("test_code"),
                                stripeProduct,
                            ),
                        ),
                    )

                val messageCallback = mockk<(PaywallMessage, StripeProductType, List<String>) -> Unit>(relaxed = true)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("starting checkout session") {
                    assert(!redeemer.isCheckoutInProgress) // Initially false

                    redeemer.startCheckoutSession(checkoutId, messageCallback)
                    testScheduler.advanceUntilIdle() // Let all coroutines complete

                    Then("isCheckoutInProgress should be false after completion") {
                        assert(!redeemer.isCheckoutInProgress)
                        verify(exactly = 1) {
                            messageCallback(
                                PaywallMessage.TransactionComplete,
                                any(),
                                any(),
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `test startCheckoutSession with immediate completion`() =
        runTest(testDispatcher) {
            Given("a WebPaywallRedeemer with checkout session that completes immediately") {
                val checkoutId = "checkout_immediate"
                val redemptionCodes = listOf("immediate_code")
                val stripeProduct =
                    StripeProductType(
                        id = "immediate_product",
                        price = BigDecimal("19.99"),
                        localizedPrice = "$19.99",
                        currencyCode = "USD",
                        currencySymbol = "$",
                        priceLocale = StripeProductType.PriceLocale("", "", "", ""),
                        stripeSubscriptionPeriod = null,
                        subscriptionIntroOffer = null,
                        entitlements = emptyList(),
                    )

                coEvery { network.checkoutStatus(checkoutId) } returns
                    Either.Success(
                        CheckoutStatusResponse(
                            CheckoutStatus.Completed(
                                redemptionCodes,
                                stripeProduct,
                            ),
                        ),
                    )

                val messageCallback = mockk<(PaywallMessage, StripeProductType, List<String>) -> Unit>(relaxed = true)

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
                        deepLinkReferrer,
                        network,
                        storage,
                        willRedeemLink = {},
                        didRedeemLink = {},
                        maxAge,
                        getActiveDeviceEntitlements,
                        getUserId,
                        getDeviceId,
                        getAlias,
                        track,
                        setSubscriptionStatus,
                        isPaywallVisible,
                        showRestoreDialogAndDismiss,
                        currentPaywallEntitlements,
                        getPaywallInfo = { PaywallInfo.empty() },
                        trackRestorationFailed = {},
                        isWebToAppEnabled = { true },
                        receipts = { listOf(TransactionReceipt("mock", "orderId")) },
                        getExternalAccountId = { "" },
                        getIntegrationProps = { emptyMap() },
                    )

                When("starting checkout session that completes immediately") {
                    redeemer.startCheckoutSession(checkoutId, messageCallback)
                    testScheduler.advanceUntilIdle() // Let all coroutines complete

                    Then("it should call callback only once and clear state") {
                        coVerify(exactly = 1) { network.checkoutStatus(checkoutId) }
                        verify(exactly = 1) { messageCallback(PaywallMessage.TransactionComplete, stripeProduct, redemptionCodes) }
                        assert(!redeemer.isCheckoutInProgress)
                    }
                }
            }
        }
}
