package com.superwall.sdk.web

import android.content.Context
import com.superwall.sdk.Superwall
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.misc.asEither
import com.superwall.sdk.misc.fold
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.network.Network
import kotlinx.coroutines.launch

class WebPaywallRedeemer(
    val context: Context,
    val ioScope: IOScope,
    val deepLinkReferrer: CheckForReferral,
    val network: Network,
    val setEntitlementStatus: (List<Entitlement>) -> Unit,
    val getUserId: () -> UserId = { UserId(Superwall.instance.userId) },
    val getDeviceId: () -> DeviceVendorId = { DeviceVendorId(Superwall.instance.vendorId) },
) {
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

    suspend fun redeem(codes: List<String>) =
        network
            .redeemToken(
                codes,
                getUserId(),
                getDeviceId(),
            ).fold({
                if (it.entitlements.isNotEmpty()) {
                    setEntitlementStatus(it.entitlements)
                }
            }, {
                Logger.debug(
                    LogLevel.error,
                    LogScope.webEntitlements,
                    "Failed to redeem purchase token",
                    info = mapOf(),
                )
            })

    suspend fun checkForWebEntitlements(
        userId: String,
        deviceId: DeviceVendorId,
    ) = asEither {
        val webEntitlementsByUser = network.webEntitlementsByUserId(UserId(userId))
        val webEntitlementsByDevice = network.webEntitlementsByDeviceID(deviceId)

        val entitlements =
            (webEntitlementsByUser.getSuccess()?.entitlements ?: listOf())
                .plus(
                    webEntitlementsByDevice.getSuccess()?.entitlements ?: listOf(),
                )

        entitlements.toSet()
    }
}
