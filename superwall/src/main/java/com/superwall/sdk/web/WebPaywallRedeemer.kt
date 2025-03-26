package com.superwall.sdk.web

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.asEither
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.map
import com.superwall.sdk.models.entitlements.CustomerInfo
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.entitlements.SourceType
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
    val onRedemptionResult: (
        redemptionResult: RedemptionResult,
        customerInfo: CustomerInfo,
    ) -> Unit,
    val maxAge: () -> Long,
    val setEntitlementStatus: (List<Entitlement>) -> Unit,
    val getActiveEntitlements: () -> Set<Entitlement> = {
        Superwall.instance.entitlements.active
    },
    val getUserId: () -> UserId = { UserId(Superwall.instance.userId) },
    val getDeviceId: () -> DeviceVendorId = { DeviceVendorId(Superwall.instance.vendorId) },
    val getAliasId: () -> String? = { Superwall.instance.dependencyContainer.identityManager.aliasId },
    val track: suspend (Trackable) -> Unit = {
        Superwall.instance.track(it)
    },
    val offDeviceSubscriptionsDidChange: suspend (customerInfo: CustomerInfo) -> Unit,
) {
    private var pollingJob: Job? = null

    init {
        ioScope.launch {
            checkForRefferal()
            startPolling()
        }
    }

    private fun mergeEntitlements(
        deviceEntitlements: Set<Entitlement>,
        webEntitlements: Set<Entitlement>,
    ): Set<Entitlement> {
        val deviceWithWeb =
            deviceEntitlements.map {
                if (webEntitlements.contains(it)) {
                    it.copy(source = it.source + SourceType.WEB)
                } else {
                    it
                }
            }
        val onlyOnWeb =
            webEntitlements.filter {
                !deviceEntitlements.contains(it)
            }
        return (deviceWithWeb + onlyOnWeb).toSet()
    }

    suspend fun checkForRefferal() =
        asEither {
            deepLinkReferrer
                .checkForReferral()
                .fold(
                    onSuccess = {
                        redeem(it)
                    },
                    onFailure = { throw it },
                )
        }

    suspend fun redeem(code: String?) {
        // We want to keep track of the codes that have been retrieved by the user
        val latestResponse = storage.read(LatestRedemptionResponse)
        track(
            InternalSuperwallEvent.Redemptions(
                InternalSuperwallEvent.Redemptions.RedemptionState.Start,
            ),
        )
        var allCodes = latestResponse?.allCodes?.toMutableList() ?: mutableListOf()
        var isFirstRedemption = true
        if (allCodes.isNotEmpty()) {
            isFirstRedemption = !allCodes.map { it.code }.contains(code)
        }
        if (code != null) {
            allCodes.add(Redeemable(code, isFirstRedemption))
        }
        network
            .redeemToken(
                allCodes,
                getUserId(),
                getAliasId(),
                getDeviceId(),
            ).map {
                it.copy(
                    entitlements =
                        it.entitlements.map {
                            it.copy(source = setOf(SourceType.WEB))
                        },
                )
            }.fold({
                track(
                    InternalSuperwallEvent.Redemptions(
                        InternalSuperwallEvent.Redemptions.RedemptionState.Complete(code ?: ""),
                    ),
                )

                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.webEntitlements,
                    message = "Entitlement redemption done",
                    info = mapOf("code" to (code ?: "")),
                )
                storage.write(LatestRedemptionResponse, it)
                if (it.entitlements.isNotEmpty()) {
                    mergeEntitlements(getActiveEntitlements(), it.entitlements.toSet())

                    offDeviceSubscriptionsDidChange(
                        CustomerInfo(
                            entitlements = getActiveEntitlements(),
                            redemptions = it.codes,
                        ),
                    )
                }
                // Notify the delegate that the redemption succeeded, unless the code has not been redeemed
                val result =
                    if (it.codes.any { it.code == code }) {
                        it.codes
                    } else {
                        listOf(
                            RedemptionResult.Error(
                                code = code ?: "",
                                error = ErrorInfo("Redemption failed, code not returned"),
                            ),
                        )
                    }
                val redemptionResultForCode = result.firstOrNull { it.code == code }
                if (redemptionResultForCode != null) {
                    onRedemptionResult(
                        redemptionResultForCode,
                        CustomerInfo(
                            entitlements = getActiveEntitlements(),
                            redemptions = it.codes,
                        ),
                    )
                }
            }, onFailure = {
                track(
                    InternalSuperwallEvent.Redemptions(
                        InternalSuperwallEvent.Redemptions.RedemptionState.Fail(code ?: ""),
                    ),
                )
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.webEntitlements,
                    message = "Entitlement redemption failed",
                    info = mapOf("code" to (code ?: "")),
                    error = it,
                )
                // Notify the delegate that the redemption failed
                onRedemptionResult(
                    RedemptionResult.Error(
                        code = code ?: "",
                        error =
                            ErrorInfo(
                                it.localizedMessage ?: it.message
                                    ?: "Redemption failed, error unknown",
                            ),
                    ),
                    CustomerInfo(
                        entitlements = getActiveEntitlements(),
                        redemptions = latestResponse?.codes ?: emptyList(),
                    ),
                )

                Logger.debug(
                    LogLevel.error,
                    LogScope.webEntitlements,
                    "Failed to redeem purchase token",
                    info = mapOf(),
                )
            })
        startPolling()
    }

    suspend fun checkForWebEntitlements(
        userId: UserId,
        deviceId: DeviceVendorId,
    ) = withErrorTracking {
        val webEntitlementsByUserCall =
            ioScope.async { network.webEntitlementsByUserId(userId, deviceId) }
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
            .map {
                it.copy(source = setOf(SourceType.WEB))
            }
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
        val activeEntitlements =
            getActiveEntitlements()
                .map {
                    // If it comes only from web, then we remove it
                    if (it.source.size == 1 && it.source.contains(SourceType.WEB)) {
                        null
                    } else {
                        // Otherwise we remove the web source
                        it.copy(source = it.source.filterNot { it == SourceType.WEB }.toSet())
                    }
                }.filterNotNull()

        setEntitlementStatus(activeEntitlements)
        if (withUserCodesRemoved != null) {
            storage.write(LatestRedemptionResponse, withUserCodesRemoved)
        }
    }

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
                                val newEntitlements =
                                    mergeEntitlements(getActiveEntitlements(), it.toSet())
                                var latestRedeemResponse = storage.read(LatestRedemptionResponse)
                                latestRedeemResponse = latestRedeemResponse?.copy(entitlements = newEntitlements.toList())
                                    ?: WebRedemptionResponse(emptyList(), newEntitlements.toList())
                                storage.write(LatestRedemptionResponse, latestRedeemResponse)
                                offDeviceSubscriptionsDidChange(
                                    CustomerInfo(newEntitlements, emptyList()),
                                )
                            },
                        )
                    delay(maxAge)
                }
            }
    }
}
