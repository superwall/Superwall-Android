package com.superwall.sdk.analytics

import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.appset.AppSet
import com.google.android.gms.appset.AppSetIdInfo
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.track
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent.IntegrationAttributes
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.misc.IOScope
import com.superwall.sdk.models.attribution.AttributionProvider
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.collections.toMap
import kotlin.time.Duration.Companion.milliseconds

class AttributionManager(
    val storage: Storage,
    val track: suspend (TrackableSuperwallEvent) -> Unit,
    val ioScope: IOScope = IOScope(),
    val redeemAfterSetting: () -> Unit,
    val vendorId: () -> VendorId,
) {
    /**
     * Backing field for integration identifiers.
     */
    private var _integrationAttributes: Map<AttributionProvider, String> = emptyMap()
    private var appSetId: String? = null
    val idTask: Task<AppSetIdInfo>
    private var advertiserId: String? = null
    private var isLimitedTrackingEnabled: Boolean? = null
    private var advertiserIdJob: Job? = null

    init {
        _integrationAttributes =
            storage.read(com.superwall.sdk.storage.IntegrationAttributes) ?: emptyMap()
        advertiserIdJob =
            ioScope.launch {
                advertiserId = AdvertisingIdClient.getAdvertisingIdInfo(Superwall.instance.context).id
                isLimitedTrackingEnabled = AdvertisingIdClient.getAdvertisingIdInfo(Superwall.instance.context).isLimitAdTrackingEnabled
            }
        idTask =
            AppSet
                .getClient(Superwall.instance.context)
                .appSetIdInfo
                .addOnCompleteListener { t ->
                    appSetId = t.result.id
                }.addOnFailureListener {
                    it.printStackTrace()
                }
    }

    val integrationAttributes: Map<String, String>
        get() =
            getFullAttributionIds()

    var lastIntegrationJob: Job? = null

    internal fun getFullAttributionIds() =
        _integrationAttributes
            .map {
                it.key.rawName to it.value
            }.toMap()
            .plus(
                mapOf(
                    "aaid" to (advertiserId ?: ""),
                    "appSetid" to (appSetId ?: ""),
                    "vendorId" to vendorId().value,
                ),
            )

    fun setIntegrationAttributes(attributes: Map<AttributionProvider, String>) {
        if (lastIntegrationJob != null) {
            runCatching { lastIntegrationJob?.cancel() }
        }
        lastIntegrationJob =
            ioScope.launch {
                delay(500.milliseconds)
                appSetId = Tasks.await(idTask).id
                advertiserIdJob?.asCompletableFuture()?.await()
                withErrorTracking {
                    _integrationAttributes = attributes

                    val merged = getFullAttributionIds()
                    storage.write(
                        com.superwall.sdk.storage.IntegrationAttributes,
                        _integrationAttributes,
                    )
                    ioScope.launch {
                        track(IntegrationAttributes(merged))
                        redeemAfterSetting()
                    }
                }
            }
    }
}
