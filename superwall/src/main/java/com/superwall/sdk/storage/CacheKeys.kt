package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.models.entitlements.EntitlementStatus
import com.superwall.sdk.models.geo.GeoInfo
import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.models.transactions.SavedTransaction
import com.superwall.sdk.models.triggers.Experiment
import com.superwall.sdk.models.triggers.ExperimentID
import com.superwall.sdk.store.abstractions.transactions.StoreTransaction
import com.superwall.sdk.utilities.DateUtils
import com.superwall.sdk.utilities.ErrorTracking
import com.superwall.sdk.utilities.dateFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.TimeZone

enum class SearchPathDirectory {
    // / Saves to the caches directory, which can be cleared by the system at any time.
    CACHE,

    // / Specific to the user.
    USER_SPECIFIC_DOCUMENTS,

    // / Specific to the app as a whole.
    APP_SPECIFIC_DOCUMENTS,

    ;

    fun fileDirectory(context: Context): File =
        when (this) {
            CACHE -> context.cacheDir
            USER_SPECIFIC_DOCUMENTS ->
                context.getDir(
                    "user_specific_document_dir",
                    Context.MODE_PRIVATE,
                )

            APP_SPECIFIC_DOCUMENTS ->
                context.getDir(
                    "app_specific_document_dir",
                    Context.MODE_PRIVATE,
                )
        }
}

interface Storable<T> {
    val key: String
    val directory: SearchPathDirectory
    val serializer: KSerializer<T>

    fun file(context: Context): File =
        File(
            directory.fileDirectory(context).also {
                if (!it.exists()) {
                    it.mkdir()
                }
            },
            key.toMD5(),
        )
}

fun String.toMD5(): String {
    val md5 = MessageDigest.getInstance("MD5")
    md5.update(this.toByteArray(Charsets.UTF_8))
    val digest = md5.digest()
    return digest.fold("", { str, it -> str + "%02x".format(it) })
}

//region Cache Keys

object AppUserId : Storable<String> {
    override val key: String
        get() = "store.appUserId"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<String>
        get() = String.serializer()
}

object AliasId : Storable<String> {
    override val key: String
        get() = "store.aliasId"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<String>
        get() = String.serializer()
}

object Seed : Storable<Int> {
    override val key: String
        get() = "store.seed"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Int>
        get() = Int.serializer()
}

object DidTrackAppInstall : Storable<Boolean> {
    override val key: String
        get() = "store.didTrackAppInstall"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Boolean>
        get() = Boolean.serializer()
}

object DidTrackFirstSeen : Storable<Boolean> {
    override val key: String
        get() = "store.didTrackFirstSeen.v2"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Boolean>
        get() = Boolean.serializer()
}

object DidTrackFirstSession : Storable<Boolean> {
    override val key: String
        get() = "store.didTrackFirstSession"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Boolean>
        get() = Boolean.serializer()
}

object UserAttributes : Storable<
    Map<
        String,
        @kotlinx.serialization.Serializable(with = AnySerializer::class)
        Any,
    >,
> {
    override val key: String
        get() = "store.userAttributes"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Map<String, Any>>
        get() = MapSerializer(String.serializer(), AnySerializer)
}

object Transactions : Storable<StoreTransaction> {
    override val key: String
        get() = "store.transactions.v2"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.CACHE

    override val serializer: KSerializer<StoreTransaction>
        get() = StoreTransaction.serializer()
}

object LastPaywallView : Storable<Date> {
    override val key: String
        get() = "store.lastPaywallView"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Date>
        get() = DateSerializer
}

object TotalPaywallViews : Storable<Int> {
    override val key: String
        get() = "store.totalPaywallViews"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Int>
        get() = Int.serializer()
}

object ConfirmedAssignments : Storable<Map<ExperimentID, Experiment.Variant>> {
    override val key: String
        get() = "store.confirmedAssignments"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Map<ExperimentID, Experiment.Variant>>
        get() = MapSerializer(ExperimentID.serializer(), Experiment.Variant.serializer())
}

object SdkVersion : Storable<String> {
    override val key: String
        get() = "store.sdkVersion"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<String>
        get() = String.serializer()
}

object StoredEntitlementStatus : Storable<EntitlementStatus> {
    override val key: String
        get() = "store.entitlementStatus"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<EntitlementStatus>
        get() = EntitlementStatus.serializer()
}

object StoredEntitlementsByProductId : Storable<Map<String, Set<Entitlement>>> {
    override val key: String
        get() = "store.entitlementByProductId"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Map<String, Set<Entitlement>>>
        get() = MapSerializer(String.serializer(), SetSerializer(Entitlement.serializer()))
}

object SurveyAssignmentKey : Storable<String> {
    override val key: String
        get() = "store.surveyAssignmentKey"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<String>
        get() = String.serializer()
}

object DisableVerboseEvents : Storable<Boolean> {
    override val key: String
        get() = "store.disableVerboseEvents"

    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS

    override val serializer: KSerializer<Boolean>
        get() = Boolean.serializer()
}

internal object ErrorLog : Storable<ErrorTracking.ErrorOccurence> {
    override val key: String
        get() = "store.errorLog"
    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.CACHE
    override val serializer: KSerializer<ErrorTracking.ErrorOccurence>
        get() = ErrorTracking.ErrorOccurence.serializer()
}

internal object LatestConfig : Storable<Config> {
    override val key: String
        get() = "store.configCache"
    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.CACHE
    override val serializer: KSerializer<Config>
        get() = Config.serializer()
}

internal object LatestGeoInfo : Storable<GeoInfo> {
    override val key: String
        get() = "store.geoInfoCache"
    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.CACHE
    override val serializer: KSerializer<GeoInfo>
        get() = GeoInfo.serializer()
}

internal object SavedTransactions : Storable<Set<SavedTransaction>> {
    override val key: String
        get() = "store.savedTransactions"
    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS
    override val serializer: KSerializer<Set<SavedTransaction>>
        get() = SetSerializer(SavedTransaction.serializer())
}

internal object PurchasingProductdIds : Storable<Set<String>> {
    override val key: String
        get() = "store.purchasingProductIds"
    override val directory: SearchPathDirectory
        get() = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS
    override val serializer: KSerializer<Set<String>>
        get() = SetSerializer(String.serializer())
}
//endregion

// region Serializers

@Serializer(forClass = Date::class)
object DateSerializer : KSerializer<Date> {
    private val format =
        dateFormat(DateUtils.ISO_SECONDS_TIMEZONE).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Date", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Date,
    ) {
        val formattedDate = format.format(value)
        encoder.encodeString(formattedDate)
    }

    override fun deserialize(decoder: Decoder): Date {
        val dateString = decoder.decodeString()
        return format.parse(dateString)
            ?: throw SerializationException("Invalid date format: $dateString")
    }
}

// endregion
