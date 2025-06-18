package com.superwall.sdk.network

import android.net.Uri
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.assignment.Assignment
import com.superwall.sdk.models.assignment.AssignmentPostback
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.enrichment.EnrichmentRequest
import com.superwall.sdk.models.entitlements.Redeemable
import com.superwall.sdk.models.entitlements.TransactionReceipt
import com.superwall.sdk.models.entitlements.WebEntitlements
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.UserId
import com.superwall.sdk.models.internal.WebRedemptionResponse
import com.superwall.sdk.models.paywall.Paywall
import kotlin.time.Duration

interface SuperwallAPI {
    suspend fun sendEvents(events: EventsRequest): Either<Unit, NetworkError>

    suspend fun getConfig(isRetryingCallback: suspend () -> Unit): Either<Config, NetworkError>

    suspend fun confirmAssignments(confirmableAssignments: AssignmentPostback): Either<Unit, NetworkError>

    suspend fun getPaywall(
        identifier: String? = null,
        event: EventData? = null,
    ): Either<Paywall, NetworkError>

    suspend fun getPaywalls(isForDebugging: Boolean = false): Either<List<Paywall>, NetworkError>

    suspend fun getEnrichment(
        enrichmentRequest: EnrichmentRequest,
        maxRetry: Int,
        timeout: Duration,
    ): Either<Enrichment, NetworkError>

    suspend fun getAssignments(): Either<List<Assignment>, NetworkError>

    suspend fun webEntitlementsByUserId(
        userId: UserId,
        deviceId: DeviceVendorId,
    ): Either<WebEntitlements, NetworkError>

    suspend fun webEntitlementsByDeviceID(deviceId: DeviceVendorId): Either<WebEntitlements, NetworkError>

    suspend fun redeemToken(
        token: List<Redeemable>,
        userId: UserId?,
        aliasId: String?,
        vendorId: DeviceVendorId,
        receipts: List<TransactionReceipt>,
    ): Either<WebRedemptionResponse, NetworkError>

    suspend fun fetchRemoteFile(
        url: Uri,
        id: String,
    ): Either<FileResponse, NetworkError>
}
