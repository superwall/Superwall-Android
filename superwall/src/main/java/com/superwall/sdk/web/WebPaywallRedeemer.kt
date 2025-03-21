package com.superwall.sdk.web

import android.content.Context
import android.util.Log
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
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
    val getActiveEntitlements: () -> Set<Entitlement> = {
        Log.e(
            "Inside redeemer",
            "Getting active entitlements - ${Superwall.instance.entitlements.active.map { "id : ${it.id}, source: ${it.source}" }}",
        )
        Superwall.instance.entitlements.active
    },
    val getUserId: () -> UserId = { UserId(Superwall.instance.userId) },
    val getDeviceId: () -> DeviceVendorId = { DeviceVendorId(Superwall.instance.vendorId) },
    val getAliasId: () -> String? = { Superwall.instance.dependencyContainer.identityManager.aliasId },
    val track: suspend (Trackable) -> Unit = {
        Superwall.instance.track(it)
    },
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
    ) {
        Log.e("Reedeming", "Merging: Device entitlements $deviceEntitlements")
        Log.e("Reedeming", "Merging: Web entitlements $webEntitlements")
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
        Log.e("Reedeming", "Merging: Merged -  ${deviceWithWeb + onlyOnWeb}")
        setEntitlementStatus(deviceWithWeb + onlyOnWeb)
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
        try {
            // We want to keep track of the codes that have been retrieved by the user
            val latestResponse = storage.read(LatestRedemptionResponse)
            Log.e("Reedeming", "Code: Latest response: $latestResponse")
            Log.e("Reedeming", "Code: Latest response: $latestResponse")
            var allCodes = latestResponse?.allCodes?.toMutableList() ?: mutableListOf()
            var isFirstRedemption = true
            if (allCodes.isNotEmpty()) {
                isFirstRedemption = !allCodes.map { it.code }.contains(code)
            }
            Log.e("Reedeming", "Code $code")
            if (code != null) {
                Log.e("Redeeming", "Code not null, adding to all codes")
                allCodes.add(Redeemable(code, isFirstRedemption))
            } else {
                Log.e("Redeeming", "Code null, $allCodes")
            }
            Log.e("Redeeming", "Allcodes: $allCodes")
            Log.e("Redeeming", "UserId: ${getUserId().value}")
            Log.e("Redeeming", "AliasId: ${getAliasId()}")
            Log.e("Redeeming", "DeviceId: ${getDeviceId().value}")
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
                    Log.e("Reedeming", "Code: Response $code 200 - results")
                    Log.e("Redeeming", "Code: Results: $it")
                    Logger.debug(
                        logLevel = LogLevel.debug,
                        scope = LogScope.webEntitlements,
                        message = "Entitlement redemption done",
                        info = mapOf("code" to (code ?: "")),
                    )
                    storage.write(LatestRedemptionResponse, it)
                    if (it.entitlements.isNotEmpty()) {
                        mergeEntitlements(getActiveEntitlements(), it.entitlements.toSet())
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
                    Log.e(
                        "Reedeming",
                        "Code: Calling on result for ${result.first { it.code == code }}",
                    )
                    onRedemptionResult(
                        result.first { it.code == code },
                        CustomerInfo(
                            entitlement = getActiveEntitlements(),
                            redemptions = it.codes,
                        ),
                    )
                }, {
                    it.printStackTrace()
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
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    suspend fun checkForWebEntitlements(
        userId: UserId,
        deviceId: DeviceVendorId,
    ) = asEither {
        Log.e("Reedeming", "Web Entitlements: Checking for web entitlements")
        val webEntitlementsByUser = network.webEntitlementsByUserId(userId, deviceId)
        val webEntitlementsByDevice = network.webEntitlementsByDeviceID(deviceId)

        Log.e(
            "Reedeming",
            "Web Entitlements for user: ${webEntitlementsByUser.getSuccess()?.entitlements}",
        )
        Log.e(
            "Reedeming",
            "Web Entitlements for device: ${webEntitlementsByDevice.getSuccess()?.entitlements}",
        )

        val entitlements =
            (webEntitlementsByUser.getSuccess()?.entitlements ?: listOf())
                .plus(
                    webEntitlementsByDevice.getSuccess()?.entitlements ?: listOf(),
                )
        entitlements
            .toSet()
            .map {
                it.copy(source = setOf(SourceType.WEB))
            }.let {
                Log.e("Reedeming", "Web Entitlements: Returning $it")
                it
            }
    }

    fun clear(ownership: RedemptionOwnershipType) {
        Log.e("Reedeming", "Calling clear")
        val latestResponse = storage.read(LatestRedemptionResponse)
        // User has not redeemed anything yet
        // Find success codes that belong to the user
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
        Log.e("Reedeming", "Clear: User codes to remove $userCodesToRemove")
        // Find entitlements belonging to those codes
        val userCodeEntitlementsToRemove =
            userCodesToRemove?.flatMap { userCode -> userCode.redemptionInfo.entitlements }
        Log.e("Reedeming", "Clear: User code entitlements to remove $userCodesToRemove")

        // Create a new response with the filtered codes and entitlements
        // (we do not need to check source here as they are all web)
        val withUserCodesRemoved =
            latestResponse?.copy(
                codes = latestResponse.codes.filterNot { it in userCodesToRemove.orEmpty() },
                entitlements = latestResponse.entitlements.filterNot { it in userCodeEntitlementsToRemove.orEmpty() },
            )
        Log.e("Reedeming", "Clear: Response with user codes removed $withUserCodesRemoved")

        // Get active entitlements that remain after removing web sources or ones from the web
        val activeEntitlements =
            getActiveEntitlements()
                .map {
                    Log.e("Redeeming", "Clear: Active entitlement $it")
                    if (it.id in
                        userCodeEntitlementsToRemove
                            ?.map { it.id }
                            .orEmpty() ||
                        latestResponse == null
                    ) {
                        Log.e("Redeeming", "Clear: Active entitlement in removal")
                        // If it comes only from web, then we remove it
                        if (it.source.size == 1 && it.source.contains(SourceType.WEB)) {
                            null
                        } else {
                            // Otherwise we remove the web source
                            it.copy(source = it.source.filterNot { it == SourceType.WEB }.toSet())
                        }
                    } else {
                        Log.e("Redeeming", "Clear: Active entitlement not removed")
                        it
                    }
                }.filterNotNull()

        Log.e("Reedeming", "Clear: New active entitlements $activeEntitlements")

        setEntitlementStatus(activeEntitlements)
        if (withUserCodesRemoved != null) {
            storage.write(LatestRedemptionResponse, withUserCodesRemoved)
        }
        ioScope.launch {
            redeem(null)
            startPolling()
        }
    }

    fun startPolling(maxAge: Long = maxAge()) {
        pollingJob?.cancel()
        pollingJob =
            (ioScope + Dispatchers.IO).launch {
                while (true) {
                    Log.e("Redeeming", "Polling: Checking for web entitlements")
                    checkForWebEntitlements(getUserId(), getDeviceId())
                        .fold(
                            onFailure = {
                                Log.e("Redeeming", "Polling: failed")
                                it.printStackTrace()
                            },
                            onSuccess = {
                                Log.e("Redeeming", "Polling: success")
                                mergeEntitlements(getActiveEntitlements(), it.toSet())
                            },
                        )
                    delay(maxAge)
                }
            }
    }
}
