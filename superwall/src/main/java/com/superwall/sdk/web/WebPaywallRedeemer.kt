package com.superwall.sdk.web

import android.content.Context
import android.util.Log
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Redemptions
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.Redemptions.RedemptionState
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.asEither
import com.superwall.sdk.misc.fold
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.entitlements.WebEntitlements
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.ErrorInfo
import com.superwall.sdk.models.internal.RedemptionOwnershipType
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.network.Network
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class WebPaywallRedeemer(
    val context: Context,
    val ioScope: IOScope,
    val deepLinkReferrer: CheckForReferral,
    val network: Network,
    val storage: Storage,
    val willRedeemLink: () -> Unit,
    val didRedeemLink: (
        redemptionResult: RedemptionResult,
    ) -> Unit,
    val maxAge: () -> Long,
    val getActiveDeviceEntitlements: () -> Set<Entitlement> = {
        Superwall.instance.entitlements.activeDeviceEntitlements
    },
    val getUserId: () -> UserId? = {
        Superwall.instance.dependencyContainer.identityManager.appUserId?.let {
            UserId(
                it,
            )
        }
    },
    val getDeviceId: () -> DeviceVendorId = { DeviceVendorId(Superwall.instance.vendorId) },
    val getAliasId: () -> String? = { Superwall.instance.dependencyContainer.identityManager.aliasId },
    val track: suspend (Trackable) -> Unit = {
        Superwall.instance.track(it)
    },
    val setActiveWebEntitlements: suspend (Set<Entitlement>) -> Unit,
    val setSubscriptionStatus: (SubscriptionStatus) -> Unit,
    val isPaywallVisible: suspend () -> Boolean = { Superwall.instance.isPaywallPresented },
    val showRestoreDialogAndDismiss: suspend () -> Unit = { },
    val currentPaywallEntitlements: () -> Set<Entitlement>,
) {
    private var pollingJob: Job? = null

    init {
        ioScope.launch {
            checkForRefferal()
            startPolling()
        }
    }

    suspend fun checkForRefferal() =
        asEither {
            deepLinkReferrer
                .checkForReferral()
                .fold(
                    onSuccess = {
                        redeem(RedeemType.Code(it))
                    },
                    onFailure = { throw it },
                )
        }

    sealed interface RedeemType {
        data class Code(
            val code: String,
        ) : RedeemType

        object Existing : RedeemType
    }

    suspend fun redeem(redemption: RedeemType) {
        // We want to keep track of the codes that have been retrieved by the user
        val latestResponse = storage.read(LatestRedemptionResponse)
        if (redemption is RedeemType.Code) {
            track(Redemptions(RedemptionState.Start))
            willRedeemLink()
        }
        val allCodes = latestResponse?.allCodes?.toMutableList() ?: mutableListOf()
        var isFirstRedemption = true
        if (allCodes.isNotEmpty() && redemption is RedeemType.Code) {
            isFirstRedemption = !allCodes.map { it.code }.contains(redemption.code)
        }
        if (redemption is RedeemType.Code) {
            allCodes.add(Redeemable(redemption.code, isFirstRedemption))
        }
        Log.e(
            "Redemption",
            "Codes: $allCodes \n user ${getUserId()} Alias ${getAliasId()} device ${getDeviceId()}",
        )
        network
            .redeemToken(
                allCodes,
                getUserId(),
                getAliasId(),
                getDeviceId(),
            ).fold(onSuccess = {
                if (redemption is RedeemType.Code) {
                    track(
                        Redemptions(
                            RedemptionState.Complete(redemption.code),
                        ),
                    )
                }

                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.webEntitlements,
                    message = "Entitlement redemption done",
                    info = mapOf("code" to ((redemption as? RedeemType.Code)?.code ?: "")),
                )
                storage.write(LatestRedemptionResponse, it)
                if (it.entitlements.isNotEmpty()) {
                    setActiveWebEntitlements(it.entitlements.toSet())
                }
                // Notify the delegate that the redemption succeeded, unless the code has not been redeemed
                val result =
                    if (redemption is RedeemType.Code && it.codes.any { it.code == redemption.code }) {
                        it.codes
                    } else {
                        listOf(
                            RedemptionResult.Error(
                                code = (redemption as? RedeemType.Code?)?.code ?: "",
                                error = ErrorInfo("Redemption failed, code not returned"),
                            ),
                        )
                    }
                if (redemption is RedeemType.Code) {
                    val redemptionResultForCode = result.firstOrNull { it.code == redemption.code }
                    if (redemptionResultForCode != null) {
                        didRedeemLink(
                            redemptionResultForCode,
                        )
                    }
                }

                if (isPaywallVisible() && it.entitlements.containsAll(currentPaywallEntitlements())) {
                    showRestoreDialogAndDismiss()
                }
            }, onFailure = {
                it.printStackTrace()
                if (redemption is RedeemType.Code) {
                    track(
                        Redemptions(
                            RedemptionState.Fail(redemption.code),
                        ),
                    )
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.webEntitlements,
                        message = "Entitlement redemption failed",
                        info = mapOf("code" to redemption.code),
                        error = it,
                    )
                    // Notify the delegate that the redemption failed
                    didRedeemLink(
                        RedemptionResult.Error(
                            code = redemption.code ?: "",
                            error =
                                ErrorInfo(
                                    it.localizedMessage ?: it.message
                                        ?: "Redemption failed, error unknown",
                                ),
                        ),
                    )
                }
            })
        startPolling()
    }

    suspend fun checkForWebEntitlements(
        userId: UserId?,
        deviceId: DeviceVendorId,
    ) = withErrorTracking {
        val webEntitlementsByUserCall =
            ioScope.async {
                // If no user ID, we don't call this just the device ID
                if (userId != null) {
                    network.webEntitlementsByUserId(userId, deviceId)
                } else {
                    Either.Success(WebEntitlements(emptyList()))
                }
            }
        val webEntitlementsByDeviceCall =
            ioScope.async { network.webEntitlementsByDeviceID(deviceId) }
        val (webEntitlementsByUser, webEntitlementsByDevice) =
            listOf(
                webEntitlementsByUserCall,
                webEntitlementsByDeviceCall,
            ).awaitAll()
        val entitlements =
            (webEntitlementsByUser.getSuccess()?.entitlements ?: listOf())
                .plus(
                    webEntitlementsByDevice.getSuccess()?.entitlements ?: listOf(),
                )
        entitlements
            .toSet()
    }

    fun clear(ownership: RedemptionOwnershipType) {
        val latestResponse = storage.read(LatestRedemptionResponse)
        // User has not redeemed anything yet
        // Find success codes that belong to the user
        val userCodesToRemove =
            latestResponse?.codes?.filterIsInstance<RedemptionResult.Success>()?.filter {
                ownership ==
                    RedemptionOwnershipType.AppUser
            }
        // Find entitlements belonging to those codes
        val userCodeEntitlementsToRemove =
            userCodesToRemove?.flatMap { userCode -> userCode.redemptionInfo.entitlements }

        // Create a new response with the filtered codes and entitlements
        // (we do not need to check source here as they are all web)
        val withUserCodesRemoved =
            latestResponse?.copy(
                codes = latestResponse.codes.filterNot { it in userCodesToRemove.orEmpty() },
                entitlements = latestResponse.entitlements.filterNot { it in userCodeEntitlementsToRemove.orEmpty() },
            )

        // Get active entitlements that remain after removing web sources or ones from the web
        ioScope.launch {
            setActiveWebEntitlements(emptySet())
            setSubscriptionStatus(SubscriptionStatus.Active(getActiveDeviceEntitlements()))
        }
        if (withUserCodesRemoved != null) {
            storage.write(LatestRedemptionResponse, withUserCodesRemoved)
        }
        ioScope.launch {
            startPolling(maxAge())
            redeem(RedeemType.Existing)
        }
    }

    // TODO Do not poll if web2app not enabled
    fun startPolling(maxAge: Long = maxAge()) {
        pollingJob?.cancel()
        pollingJob =
            (ioScope + Dispatchers.IO).launch {
                while (true) {
                    checkForWebEntitlements(getUserId(), getDeviceId())
                        .fold(
                            onFailure = {
                                it.printStackTrace()
                            },
                            onSuccess = {
                                setActiveWebEntitlements(it)
                                val newEntitlements = it
                                setSubscriptionStatus(SubscriptionStatus.Active(it + getActiveDeviceEntitlements()))
                                var latestRedeemResponse = storage.read(LatestRedemptionResponse)
                                latestRedeemResponse =
                                    latestRedeemResponse?.copy(entitlements = newEntitlements.toList())
                                        ?: WebRedemptionResponse(
                                            emptyList(),
                                            newEntitlements.toList(),
                                        )
                                storage.write(LatestRedemptionResponse, latestRedeemResponse)
                            },
                        )
                    delay(maxAge)
                }
            }
    }
}
