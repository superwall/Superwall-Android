package com.superwall.sdk.network.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.superwall.sdk.BuildConfig
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.DefaultClassifierDataFactory
import com.superwall.sdk.analytics.DeviceClassifier
import com.superwall.sdk.analytics.Tier
import com.superwall.sdk.dependencies.ActiveEntitlementsFactory
import com.superwall.sdk.dependencies.CustomerInfoFactory
import com.superwall.sdk.dependencies.ExperimentalPropertiesFactory
import com.superwall.sdk.dependencies.IdentityInfoFactory
import com.superwall.sdk.dependencies.IdentityManagerFactory
import com.superwall.sdk.dependencies.LocaleIdentifierFactory
import com.superwall.sdk.dependencies.OptionsFactory
import com.superwall.sdk.dependencies.StoreTransactionFactory
import com.superwall.sdk.dependencies.StorefrontCountryFactory
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.identity.setUserAttributes
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.Either
import com.superwall.sdk.misc.then
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.enrichment.Enrichment
import com.superwall.sdk.models.enrichment.EnrichmentRequest
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.internal.DeviceVendorId
import com.superwall.sdk.models.internal.VendorId
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.paywall.view.webview.templating.models.DeviceTemplate
import com.superwall.sdk.storage.LastPaywallView
import com.superwall.sdk.storage.LatestEnrichment
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.ReviewCount
import com.superwall.sdk.storage.ReviewData
import com.superwall.sdk.storage.TotalPaywallViews
import com.superwall.sdk.storage.core_data.convertToJsonElement
import com.superwall.sdk.utilities.DateUtils
import com.superwall.sdk.utilities.dateFormat
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.threeten.bp.Instant
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.time.Duration

enum class InterfaceStyle(
    val rawValue: String,
) {
    LIGHT("Light"),
    DARK("Dark"),
}

class DeviceHelper(
    private val context: Context,
    val storage: LocalStorage,
    val network: SuperwallAPI,
    val factory: Factory,
    private val classifier: DeviceClassifier = DeviceClassifier(DefaultClassifierDataFactory { context }),
) {
    interface Factory :
        IdentityInfoFactory,
        LocaleIdentifierFactory,
        JsonFactory,
        StoreTransactionFactory,
        IdentityManagerFactory,
        ExperimentalPropertiesFactory,
        OptionsFactory,
        CustomerInfoFactory,
        ActiveEntitlementsFactory,
        StorefrontCountryFactory

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    private val appInstallDate = Date(appInfo.firstInstallTime)

    fun daysSince(date: Date): Int {
        val fromDate = Instant.ofEpochMilli(date.time)
        val toDate = Instant.now()
        val duration =
            org.threeten.bp.Duration
                .between(fromDate, toDate)
        return duration.toDays().toInt()
    }

    fun minutesSince(date: Date): Int {
        val fromDate = Instant.ofEpochMilli(date.time)
        val toDate = Instant.now()
        val duration =
            org.threeten.bp.Duration
                .between(fromDate, toDate)
        return duration.toMinutes().toInt()
    }

    fun hoursSince(date: Date): Int {
        val fromDate = Instant.ofEpochMilli(date.time)
        val toDate = Instant.now()
        val duration =
            org.threeten.bp.Duration
                .between(fromDate, toDate)
        return duration.toHours().toInt()
    }

    fun monthsSince(date: Date): Int {
        val fromDate = Instant.ofEpochMilli(date.time)
        val toDate = Instant.now()
        val duration =
            org.threeten.bp.Duration
                .between(fromDate, toDate)
        return duration.toDays().toInt() / 30
    }

    private val daysSinceInstall: Int
        get() {
            val fromDate = Instant.ofEpochMilli(appInstallDate.time)
            val toDate = Instant.now()
            val duration =
                org.threeten.bp.Duration
                    .between(fromDate, toDate)
            return duration.toDays().toInt()
        }

    private val minutesSinceInstall: Int
        get() {
            val fromDate = Instant.ofEpochMilli(appInstallDate.time)
            val toDate = Instant.now()
            val duration =
                org.threeten.bp.Duration
                    .between(fromDate, toDate)
            return duration.toMinutes().toInt()
        }

    private val totalPaywallViews: Int
        get() {
            return storage.read(TotalPaywallViews) ?: 0
        }

    private val reviewData: ReviewCount
        get() {
            return storage.read(ReviewData) ?: ReviewCount()
        }

    val reviewRequestCount: Int
        get() = reviewData.timesQueried

    suspend fun reviewRequestsTotal(): Int {
        // Use a very old date as the start date to get all records
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, 2000)
        val startDate = calendar.time
        val endDate = Date()
        return storage.coreDataManager.countEventsByNameInPeriod(
            name = "review_requested",
            startDate = startDate,
            endDate = endDate,
        )
    }

    private val lastEnrichment: MutableStateFlow<Enrichment?> =
        MutableStateFlow(storage.read(LatestEnrichment))

    internal val demandTier: String?
        get() =
            lastEnrichment
                .value
                ?.device
                ?.get("demandTier")
                ?.toString()

    internal val demandScore: Int?
        get() =
            lastEnrichment.value?.device?.get("demandScore")?.let {
                when (it) {
                    is Double -> it.toInt()
                    is Float -> it.toInt()
                    else -> it.toString().toIntOrNull()
                }
            }

    val locale: String
        get() {
            val localeIdentifier = factory.makeLocaleIdentifier()
            return localeIdentifier ?: Locale.getDefault().toString()
        }

    val appVersion: String by lazy {
        try {
            appInfo.versionName ?: "Unknown"
        } catch (e: Throwable) {
            Logger.debug(
                LogLevel.error,
                LogScope.device,
                "DeviceHelper: Failed to load version info - $e",
            )
            ""
        }
    }

    private val appVersionPadded: String by lazy { appVersion.asPadded() }

    private val enrichment: Enrichment? get() = lastEnrichment.value
    val osVersion: String
        get() = Build.VERSION.RELEASE ?: ""

    val isEmulator: Boolean
        get() = Build.DEVICE.contains("generic") || Build.DEVICE.contains("emulator")

    val model: String
        get() = Build.MODEL

    val vendorId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    val deviceId: String by lazy { DeviceVendorId(VendorId(vendorId)).value }

    val deviceTier: Tier by lazy { classifier.deviceTier() }
    var platformWrapper: String = ""
    var platformWrapperVersion: String = ""

    private val _locale: Locale = Locale.getDefault()

    private val _currency: Currency?
        get() {
            return try {
                Currency.getInstance(_locale)
            } catch (e: Throwable) {
                null
            }
        }

    val languageCode: String
        get() = _locale.language

    private val regionCode: String
        get() = _locale.country

    val currencyCode: String
        get() = _currency?.currencyCode ?: ""

    val currencySymbol: String
        get() = _currency?.symbol ?: ""

    val timezoneOffsetSeconds: Int
        get() = TimeZone.getDefault().rawOffset / 1000

    val secondsFromGMT: String
        get() = timezoneOffsetSeconds.toString()

    val screenWidth: Int
        get() = classifier.getScreenWidth()

    val screenHeight: Int
        get() = classifier.getScreenHeight()

    val devicePixelRatio: Double
        get() =
            context.resources.displayMetrics.density
                .toDouble()

    val isFirstAppOpen: Boolean
        get() = !storage.didTrackFirstSession

    val radioType: String
        @SuppressLint("MissingPermission")
        get() {

            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_NETWORK_STATE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return ""
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networkCapabilities =
                    connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                return when {
                    networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                    networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wifi"
                    else -> ""
                }
            } else {
                when (connectivityManager.activeNetworkInfo?.type) {
                    ConnectivityManager.TYPE_MOBILE -> return "Cellular"
                    ConnectivityManager.TYPE_WIFI -> return "Wifi"
                    else -> return ""
                }
            }
        }

    val bundleId: String
        get() = context.packageName

    val isSandbox: Boolean
        get() {
            // Not exactly the same as iOS, but similar
            val isDebuggable: Boolean =
                (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return isDebuggable
        }

    val urlScheme: String
        get() = context.packageName

    val appInstalledAtString: String
        get() = dateFormat(DateUtils.SIMPLE).format(appInstallDate)

    val appInstalledAtMillis: Long
        get() = appInstallDate.time

    var interfaceStyleOverride: InterfaceStyle? = null

    val fontSize: Int
        get() = (context.resources.configuration.fontScale * 16).roundToInt()

    val fontScale: Float
        get() = context.resources.configuration.fontScale

    val interfaceStyle: String
        get() {
            return interfaceStyleOverride?.rawValue ?: run {
                val style =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    } else {
                        Configuration.UI_MODE_NIGHT_UNDEFINED
                    }
                return when (style) {
                    Configuration.UI_MODE_NIGHT_NO -> "Light"
                    Configuration.UI_MODE_NIGHT_YES -> "Dark"
                    else -> "Unspecified"
                }
            }
        }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    val isLowPowerModeEnabled: String
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (powerManager.isPowerSaveMode) "true" else "false"
            } else {
                "false"
            }
        }

    /**
     * The time-derived template fields. These change continuously, so they are
     * recomputed on every [getTemplateDevice] call and overlaid onto the
     * memoized template instead of being part of its cache key.
     */
    private data class VolatileTemplateFields(
        val utcDate: String,
        val localDate: String,
        val utcTime: String,
        val localTime: String,
        val utcDateTime: String,
        val localDateTime: String,
        val daysSinceInstall: Int,
        val minutesSinceInstall: Int,
        val daysSinceLastPaywallView: Int?,
        val minutesSinceLastPaywallView: Int?,
    ) {
        /**
         * Keys must match the serial names in [DeviceTemplate]. Numeric values
         * are converted to [Double] to match how integers come out of the JSON
         * conversion in [DeviceTemplate.toDictionary].
         */
        fun asOverlay(): Map<String, Any?> =
            mapOf(
                "utcDate" to utcDate,
                "localDate" to localDate,
                "utcTime" to utcTime,
                "localTime" to localTime,
                "utcDateTime" to utcDateTime,
                "localDateTime" to localDateTime,
                "daysSinceInstall" to daysSinceInstall.toDouble(),
                "minutesSinceInstall" to minutesSinceInstall.toDouble(),
                "daysSinceLastPaywallView" to daysSinceLastPaywallView?.toDouble(),
                "minutesSinceLastPaywallView" to minutesSinceLastPaywallView?.toDouble(),
            )
    }

    /**
     * All fields are derived from a single timestamp so that e.g. `utcDate` and
     * `utcTime` can't straddle midnight.
     */
    private fun volatileTemplateFields(): VolatileTemplateFields {
        val now = Date()
        val utcTimeZone = TimeZone.getTimeZone("UTC")
        val localTimeZone = TimeZone.getDefault()
        val dateFormatter = dateFormat(DateUtils.yyyy_MM_dd)
        val timeFormatter = dateFormat(DateUtils.HH_mm_ss)
        val dateTimeFormatter = dateFormat(DateUtils.ISO_SECONDS)

        fun SimpleDateFormat.formatIn(timeZone: TimeZone): String {
            this.timeZone = timeZone
            return format(now)
        }

        val lastPaywallViewAt = storage.read(LastPaywallView)

        return VolatileTemplateFields(
            utcDate = dateFormatter.formatIn(utcTimeZone),
            localDate = dateFormatter.formatIn(localTimeZone),
            utcTime = timeFormatter.formatIn(utcTimeZone),
            localTime = timeFormatter.formatIn(localTimeZone),
            utcDateTime = dateTimeFormatter.formatIn(utcTimeZone),
            localDateTime = dateTimeFormatter.formatIn(localTimeZone),
            daysSinceInstall = daysSinceInstall,
            minutesSinceInstall = minutesSinceInstall,
            daysSinceLastPaywallView = lastPaywallViewAt?.let { daysSince(it) },
            minutesSinceLastPaywallView = lastPaywallViewAt?.let { minutesSince(it) },
        )
    }

    private val sdkVersionPadded: String by lazy { sdkVersion.asPadded() }

    val appBuildString: String by lazy { appInfo.versionCode.toString() }

    val sdkVersion: String
        get() = BuildConfig.SDK_VERSION

    val buildTime: String
        get() = BuildConfig.BUILD_TIME

    val gitSha: String
        get() = BuildConfig.GIT_SHA

    val kotlinVersion: String by lazy {
        try {
            KotlinVersion.CURRENT.toString()
        } catch (e: Throwable) {
            "UNKNOWN"
        }
    }

    /**
     * Stable fingerprint of the device/store/subscription/storage fields that can
     * affect which paywalls IF_TRUE rules preload. Compared by value to decide
     * whether to re-run preload after a state change. Excludes iOS-only fields
     * (storeFrontId/Currency, appTransactionId) and configure-time
     * localResourceIds.
     */
    suspend fun preloadFingerprint(): String {
        val customerInfoSnapshot = factory.customerInfoFlow().value.toString()
        val activeEntitlements =
            factory
                .activeEntitlements()
                .sortedBy { it.id }
                .joinToString(",") { "${it.id}:${it.type.raw}" }
        val activeProducts = factory.activeProductIds().sorted().joinToString(",")
        val reviewRequests = reviewRequestsTotal().toString()

        return listOf(
            locale,
            languageCode,
            regionCode,
            currencyCode,
            currencySymbol,
            secondsFromGMT,
            interfaceStyle,
            if (interfaceStyleOverride == null) "automatic" else "manual",
            reviewRequests,
            activeEntitlements,
            customerInfoSnapshot,
            activeProducts,
            isSandbox.toString(),
            factory.storefrontCountryCode() ?: "",
        ).joinToString("|")
    }

    suspend fun getDeviceAttributes(
        sinceEvent: EventData?,
        computedPropertyRequests: List<ComputedPropertyRequest>,
    ): Map<String, Any> {
        val dictionary = getTemplateDevice()

        val computedProperties =
            getComputedDevicePropertiesSinceEvent(
                sinceEvent,
                computedPropertyRequests,
            )
        return dictionary + computedProperties
    }

    private suspend fun getComputedDevicePropertiesSinceEvent(
        event: EventData?,
        computedPropertyRequests: List<ComputedPropertyRequest>,
    ): Map<String, Any> {
        val output = mutableMapOf<String, Any>()

        for (computedPropertyRequest in computedPropertyRequests) {
            val value =
                storage.coreDataManager.getComputedPropertySinceEvent(
                    event,
                    request = computedPropertyRequest,
                )
            value?.let {
                output[computedPropertyRequest.type.prefix + computedPropertyRequest.eventName] = it
            }
        }

        return output
    }

    private val capabilities: List<Capability> =
        listOf(
            Capability.PaywallEventReceiver(),
            Capability.MultiplePaywallUrls,
            Capability.ConfigCaching,
        )

    private val capabilitiesConfig: JsonElement by lazy { capabilities.toJson() }

    private class CachedTemplate(
        val fingerprint: String,
        val base: Map<String, Any>,
    )

    /**
     * Memoized result of the last full template build, keyed by a fingerprint of
     * every mutable input. Building the template assembles ~55 fields (several
     * of which are system IPCs) and serializes them through JSON, so it's worth
     * skipping when nothing has changed — which is the common case, since it
     * runs per rule, per evaluation.
     */
    private val templateCache = AtomicReference<CachedTemplate?>(null)

    internal val cachedTemplate: Map<String, Any>?
        get() = templateCache.get()?.base

    private val subscriptionStatusString: String?
        get() =
            Superwall.instance.subscriptionStatus.value?.let {
                when (it) {
                    is SubscriptionStatus.Active -> "ACTIVE"
                    is SubscriptionStatus.Inactive -> "INACTIVE"
                    is SubscriptionStatus.Unknown -> "UNKNOWN"
                }
            }

    /**
     * Fingerprint of every mutable, non-time-derived input to [getTemplateDevice].
     * All reads are in-memory state or cheap system lookups, so comparing this
     * against the cached value is far cheaper than rebuilding and re-serializing
     * the full template. Time-derived fields are deliberately excluded — they are
     * recomputed and overlaid on every read via [volatileTemplateFields].
     */
    private suspend fun templateFingerprint(identityInfo: IdentityInfo): String =
        listOf(
            storage.apiKey,
            identityInfo.appUserId ?: "",
            identityInfo.aliasId,
            locale,
            ((TimeZone.getDefault().rawOffset) / 1000).toString(),
            radioType,
            interfaceStyle,
            if (interfaceStyleOverride == null) "automatic" else "manual",
            fontSize.toString(),
            fontScale.toString(),
            isLowPowerModeEnabled,
            Superwall.instance.entitlements.active
                .sortedBy { it.id }
                .joinToString(",") { "${it.id}:${it.type.raw}" },
            subscriptionStatusString ?: "null",
            factory.activeProductIds().sorted().joinToString(","),
            isFirstAppOpen.toString(),
            platformWrapper,
            platformWrapperVersion,
            totalPaywallViews.toString(),
            reviewRequestCount.toString(),
            factory.storefrontCountryCode() ?: "",
        ).joinToString("|")

    private suspend fun buildDeviceTemplate(
        identityInfo: IdentityInfo,
        volatileFields: VolatileTemplateFields,
    ): DeviceTemplate =
        DeviceTemplate(
            publicApiKey = storage.apiKey,
            platform = "Android",
            appUserId = identityInfo.appUserId ?: "",
            aliases = listOf(identityInfo.aliasId),
            vendorId = vendorId,
            deviceId = deviceId,
            appVersion = appVersion,
            osVersion = osVersion,
            deviceModel = model,
            deviceLocale = locale,
            preferredLocale = locale,
            deviceLanguageCode = languageCode,
            preferredLanguageCode = languageCode,
            regionCode = regionCode,
            preferredRegionCode = regionCode,
            deviceCurrencyCode = currencyCode,
            deviceCurrencySymbol = currencySymbol,
            timezoneOffset = (TimeZone.getDefault().rawOffset) / 1000,
            radioType = radioType,
            interfaceStyle = interfaceStyle,
            fontSize = fontSize,
            fontScale = fontScale,
            isLowPowerModeEnabled = isLowPowerModeEnabled.toBoolean(),
            bundleId = bundleId,
            appInstallDate = appInstalledAtString,
            isMac = false,
            daysSinceInstall = volatileFields.daysSinceInstall,
            minutesSinceInstall = volatileFields.minutesSinceInstall,
            daysSinceLastPaywallView = volatileFields.daysSinceLastPaywallView,
            minutesSinceLastPaywallView = volatileFields.minutesSinceLastPaywallView,
            totalPaywallViews = totalPaywallViews,
            utcDate = volatileFields.utcDate,
            localDate = volatileFields.localDate,
            utcTime = volatileFields.utcTime,
            localTime = volatileFields.localTime,
            utcDateTime = volatileFields.utcDateTime,
            localDateTime = volatileFields.localDateTime,
            isSandbox = isSandbox.toString(),
            activeEntitlements =
                Superwall.instance.entitlements.active
                    .map { it.id },
            activeEntitlementsObject =
                Superwall.instance.entitlements.active
                    .map { mapOf("identifier" to it.id, "type" to it.type.raw) },
            subscriptionStatus = subscriptionStatusString,
            activeProducts = factory.activeProductIds(),
            isFirstAppOpen = isFirstAppOpen,
            sdkVersion = sdkVersion,
            sdkVersionPadded = sdkVersionPadded,
            appBuildString = appBuildString,
            appBuildStringNumber = appBuildString.toInt(),
            interfaceStyleMode = if (interfaceStyleOverride == null) "automatic" else "manual",
            capabilities = capabilities.map { it.name },
            capabilitiesConfig = capabilitiesConfig,
            platformWrapper = platformWrapper,
            platformWrapperVersion = platformWrapperVersion,
            appVersionPadded = appVersionPadded,
            deviceTier = deviceTier.raw,
            reviewRequestCount = reviewRequestCount,
            kotlinVersion = kotlinVersion,
            storeFrontCountryCode = factory.storefrontCountryCode(),
        )

    suspend fun getTemplateDevice(): Map<String, Any> {
        return withErrorTracking {
            val identityInfo = factory.makeIdentityInfo()
            val fingerprint = templateFingerprint(identityInfo)
            val volatileFields = volatileTemplateFields()

            val cached = templateCache.get()
            val base =
                if (cached != null && cached.fingerprint == fingerprint) {
                    cached.base
                } else {
                    buildDeviceTemplate(identityInfo, volatileFields)
                        .toDictionary(json)
                        .also { templateCache.set(CachedTemplate(fingerprint, it)) }
                }

            @Suppress("UNCHECKED_CAST")
            (base + volatileFields.asOverlay()) as Map<String, Any>
        }.toResult()
            .map {
                val enriched =
                    enrichment
                        ?.device
                        ?: emptyMap()
                enriched
                    .plus(it)
                    .let {
                        withErrorTracking {
                            if (factory.makeSuperwallOptions().enableExperimentalDeviceVariables) {
                                it.plus(latestExperimentalDeviceProperties())
                            } else {
                                it
                            }
                        }.getSuccess() ?: it
                    }
            }.fold(
                onSuccess = { deviceTemplate ->
                    return@fold deviceTemplate
                },
                onFailure = {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.device,
                        message = "Failed to get device template",
                        error = it,
                    )
                    return@fold emptyMap()
                },
            )
    }

    internal fun setEnrichment(enrichment: Enrichment) {
        this.lastEnrichment.value = enrichment
    }

    fun latestExperimentalDeviceProperties(): Map<String, Any> = factory.experimentalProperties()

    suspend fun getEnrichment(
        maxRetry: Int,
        timeout: Duration,
    ): Either<Enrichment, NetworkError> {
        val userAttributes =
            factory.makeIdentityManager().userAttributes.mapValues {
                it.value.convertToJsonElement()
            }

        val deviceAttributes =
            getTemplateDevice().mapValues {
                it.value.convertToJsonElement()
            }
        return network
            .getEnrichment(EnrichmentRequest(userAttributes, deviceAttributes), maxRetry, timeout)
            .then {
                lastEnrichment.value = it
            }.then {
                storage.write(LatestEnrichment, it)
                it.user.let {
                    Superwall.instance.setUserAttributes(it.toMap())
                }
                it.device.let {
                    Superwall.instance.setUserAttributes(it.toMap())
                }
            }
    }
}

internal fun String.asPadded(): String {
    val components = split("-")
    if (components.isEmpty()) {
        return ""
    }
    val versionNumber = components[0]

    var appendix = ""

    // If there is a "beta" part...
    if (components.size > 1) {
        // Separate out the number from the name, e.g. beta.1 -> [beta, 1]
        val appendixComponents = components[1].split(".")
        appendix = "-" + appendixComponents[0]

        var appendixVersion = ""

        // Pad beta number and add to appendix
        if (appendixComponents.size > 1) {
            appendixVersion =
                String.format(Locale.US, "%03d", appendixComponents[1].toIntOrNull() ?: 0)
            appendix += ".$appendixVersion"
        }
    }

    // Separate out the version numbers.
    val versionComponents = versionNumber.split(".")
    var newVersion = ""
    if (versionComponents.isNotEmpty()) {
        val major = String.format(Locale.US, "%03d", versionComponents[0].toIntOrNull() ?: 0)
        newVersion += major
    }
    if (versionComponents.size > 1) {
        val minor = String.format(Locale.US, "%03d", versionComponents[1].toIntOrNull() ?: 0)
        newVersion += ".$minor"
    }
    if (versionComponents.size > 2) {
        val patch = String.format(Locale.US, "%03d", versionComponents[2].toIntOrNull() ?: 0)
        newVersion += ".$patch"
    }

    newVersion += appendix

    return newVersion
}
