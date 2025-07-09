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
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.paywall.presentation.PaywallInfo
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

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
                                                    StoreIdentifiers.Stripe(stripeCustomerId = "123", emptyList()),
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
                    )

                When("checking for referral") {
                    redeemer.checkForRefferal()

                    Then("it should not call redeem") {
                        coVerify(exactly = 0) {
                            network.redeemToken(any(), any(), any(), any(), any(), any())
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
                coEvery { deepLinkReferrer.checkForReferral() } returns Result.success(codes)
                coEvery {
                    network.redeemToken(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns Either.Failure(NetworkError.Unknown(exception))

                redeemer =
                    WebPaywallRedeemer(
                        context,
                        IOScope(testDispatcher),
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
                    )

                When("checking for referral") {
                    redeemer.checkForRefferal()

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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
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
                    network.redeemToken(any(), any(), any(), any(), any(), any())
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
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
                        receipts = { listOf(TransactionReceipt("mock")) },
                        getExternalAccountId = { "" },
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
}
