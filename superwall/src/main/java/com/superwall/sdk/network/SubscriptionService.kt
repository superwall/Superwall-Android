package com.superwall.sdk.network

import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.entitlements.RedeemRequest
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.entitlements.WebEntitlements
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SubscriptionService(
    override val host: String,
    override val version: String,
    val factory: ApiFactory,
    json: Json,
    override val customHttpUrlConnection: CustomHttpUrlConnection,
) : NetworkService() {
    override suspend fun makeHeaders(
        isForDebugging: Boolean,
        requestId: String,
    ): Map<String, String> = factory.makeHeaders(isForDebugging, requestId)

    private val json =
        Json(json) {
            namingStrategy = null
        }

    suspend fun redeemToken(
        codes: List<Redeemable>,
        userId: UserId,
        aliasId: String?,
        vendorId: DeviceVendorId,
    ) = post<WebRedemptionResponse>(
        "redeem",
        retryCount = 0,
        body =
            json
                .encodeToString(
                    RedeemRequest(
                        userId.value.replace("Alias", "Device"), // TODO remove this,
                        userId.value,
                        aliasId,
                        codes,
                    ),
                ).toByteArray(),
    )

    suspend fun webEntitlementsByUserId(userId: UserId) = get<WebEntitlements>("users/${userId.value}/entitlements")

    suspend fun webEntitlementsByDeviceId(deviceId: DeviceVendorId) = get<WebEntitlements>("devices/${deviceId.value}/entitlements")
}
