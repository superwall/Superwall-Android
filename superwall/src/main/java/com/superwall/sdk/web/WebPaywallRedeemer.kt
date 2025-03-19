package com.superwall.sdk.web

import android.content.Context
import com.superwall.sdk.Superwall
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
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.ErrorInfo
import com.superwall.sdk.models.internal.RedemptionOwnership
import com.superwall.sdk.models.internal.RedemptionOwnershipType
import com.superwall.sdk.models.internal.RedemptionResult
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.network.Network
import com.superwall.sdk.storage.LatestRedemptionResponse
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val getActiveEntitlements: () -> Set<Entitlement> = { Superwall.instance.entitlements.active },
    val getUserId: () -> UserId = { UserId(Superwall.instance.userId) },
    val getDeviceId: () -> DeviceVendorId = { DeviceVendorId(Superwall.instance.vendorId) },
    val getAliasId: () -> String? = { Superwall.instance.dependencyContainer.identityManager.aliasId },
) {
    private var pollingJob: Job? = null

    init {
        ioScope.launch {
            checkForRefferal()
        }
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
        var allCodes = latestResponse?.allCodes ?: emptyList()
        var isFirstRedemption = true
        if (allCodes.isNotEmpty()) {
            isFirstRedemption = !allCodes.map { it.code }.contains(code)
        }
        if (code != null) {
            allCodes = allCodes + Redeemable(code, isFirstRedemption)
        }
        network
            .redeemToken(
                allCodes,
                getUserId(),
                getAliasId(),
                getDeviceId(),
            ).fold({
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.webEntitlements,
                    message = "Entitlement redemption done",
                    info = mapOf("code" to (code ?: "")),
                )
                storage.write(LatestRedemptionResponse, it)
                if (it.entitlements.isNotEmpty()) {
                    setEntitlementStatus(it.entitlements)
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
                onRedemptionResult(
                    result.first { it.code == code },
                    CustomerInfo(
                        entitlement = getActiveEntitlements(),
                        redemptions = it.codes,
                    ),
                )
            }, {
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
                                it.localizedMessage ?: it.message ?: "Redemption failed, error unknown",
                            ),
                    ),
                    CustomerInfo(
                        entitlement = getActiveEntitlements(),
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
    ) = asEither {
        val webEntitlementsByUser = network.webEntitlementsByUserId(userId)
        val webEntitlementsByDevice = network.webEntitlementsByDeviceID(deviceId)

        val entitlements =
            (webEntitlementsByUser.getSuccess()?.entitlements ?: listOf())
                .plus(
                    webEntitlementsByDevice.getSuccess()?.entitlements ?: listOf(),
                )

        entitlements.toSet()
    }

    fun clear(ownership: RedemptionOwnershipType) {
        val latestResponse = storage.read(LatestRedemptionResponse)
        // User has not redeemed anything yet
        if (latestResponse == null) return
        val userCodesToRemove =
            latestResponse?.codes?.filterIsInstance<RedemptionResult.Success>()?.filter {
                when (ownership) {
                    RedemptionOwnershipType.AppUser -> {
                        it.redemptionInfo.ownership is RedemptionOwnership.AppUser
                    }

                    RedemptionOwnershipType.Device -> {
                        false
                    }
                }
            }
        val withUserCodesRemoved =
            latestResponse.copy(codes = latestResponse.codes.filterNot { it in userCodesToRemove.orEmpty() })
        storage.write(LatestRedemptionResponse, withUserCodesRemoved)
        pollingJob?.cancel()
    }

    fun startPolling(maxAge: Long = maxAge()) {
        pollingJob?.cancel()
        pollingJob =
            (ioScope + Dispatchers.IO).launch {
                while (true) {
                    delay(maxAge)
                    checkForWebEntitlements(getUserId(), getDeviceId())
                }
            }
    }
}
