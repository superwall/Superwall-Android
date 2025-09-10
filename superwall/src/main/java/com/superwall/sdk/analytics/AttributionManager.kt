package com.superwall.sdk.analytics

import android.util.Log
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
import com.superwall.sdk.web.WebPaywallRedeemer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
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
    val vendorId: () -> VendorId
) {

    /**
     * Backing field for integration identifiers.
     */
    private var _integrationAttributes: Map<AttributionProvider, String> = emptyMap()
    private var appSetId: String? = null
    val idTask: Task<AppSetIdInfo>

    init {
        _integrationAttributes =
            storage.read(com.superwall.sdk.storage.IntegrationAttributes) ?: emptyMap()
        idTask =
            AppSet.getClient(Superwall.instance.context).appSetIdInfo.addOnCompleteListener { t ->
                    appSetId = t.result.id
                }

    }

    val integrationAttributes: Map<String, String>
        get() = _integrationAttributes.map { (provider, id) ->
                provider.rawName to id
            }.toMap()

    var lastIntegrationJob: Job? = null

    val advertiserId
        get() = AdvertisingIdClient.getAdvertisingIdInfo(Superwall.instance.context).id

    val isLimitedAdTrackingEnabled
        get() = AdvertisingIdClient.getAdvertisingIdInfo(Superwall.instance.context).isLimitAdTrackingEnabled

    internal fun getFullAttributionIds() = _integrationAttributes.map {
        it.key.rawName to it.value
    }.toMap().plus(
        mapOf(
            "aaid" to (advertiserId ?: ""),
            "appSetid" to (appSetId ?: ""),
            "vendorId" to vendorId().value
        )
    )

    fun setIntegrationAttributes(attributes: Map<AttributionProvider, String>) {
        if (lastIntegrationJob != null) {
            runCatching { lastIntegrationJob?.cancel() }
        }
        lastIntegrationJob = ioScope.launch {
            delay(500.milliseconds)
            Log.e("Testingx", "Waiting for ids")
            appSetId = Tasks.await(idTask).id
            withErrorTracking {
                _integrationAttributes = attributes

                val merged = getFullAttributionIds()
                Log.e("Testingx", "Going to merge")
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
