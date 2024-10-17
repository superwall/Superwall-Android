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
import com.superwall.sdk.dependencies.IdentityInfoFactory
import com.superwall.sdk.dependencies.LocaleIdentifierFactory
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.fold
import com.superwall.sdk.misc.then
import com.superwall.sdk.misc.toResult
import com.superwall.sdk.models.config.ComputedPropertyRequest
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.geo.GeoInfo
import com.superwall.sdk.network.JsonFactory
import com.superwall.sdk.network.SuperwallAPI
import com.superwall.sdk.paywall.vc.web_view.templating.models.DeviceTemplate
import com.superwall.sdk.storage.LastPaywallView
import com.superwall.sdk.storage.LatestGeoInfo
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.TotalPaywallViews
import com.superwall.sdk.utilities.DateUtils
import com.superwall.sdk.utilities.dateFormat
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.minutes

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
) {
    interface Factory :
        IdentityInfoFactory,
        LocaleIdentifierFactory,
        JsonFactory

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    private val appInstallDate = Date(appInfo.firstInstallTime)

    private val daysSinceInstall: Int
        get() {
            val fromDate = appInstallDate
            val toDate = Date()
            val fromInstant = fromDate.toInstant()
            val toInstant = toDate.toInstant()
            val duration = Duration.between(fromInstant, toInstant)
            return duration.toDays().toInt()
        }

    private val minutesSinceInstall: Int
        get() {
            val fromDate = appInstallDate
            val toDate = Date()
            val fromInstant = fromDate.toInstant()
            val toInstant = toDate.toInstant()
            val duration = Duration.between(fromInstant, toInstant)
            return duration.toMinutes().toInt()
        }

    private val daysSinceLastPaywallView: Int?
        get() {
            val fromDate = storage.read(LastPaywallView) ?: return null
            val toDate = Date()
            val fromInstant = fromDate.toInstant()
            val toInstant = toDate.toInstant()
            val duration = Duration.between(fromInstant, toInstant)
            return duration.toDays().toInt()
        }

    private val minutesSinceLastPaywallView: Int?
        get() {
            val fromDate = storage.read(LastPaywallView) ?: return null
            val toDate = Date()
            val fromInstant = fromDate.toInstant()
            val toInstant = toDate.toInstant()
            val duration = Duration.between(fromInstant, toInstant)
            return duration.toMinutes().toInt()
        }

    private val totalPaywallViews: Int
        get() {
            return storage.read(TotalPaywallViews) ?: 0
        }

    private val lastGeoInfo: MutableStateFlow<GeoInfo?> =
        MutableStateFlow(storage.read(LatestGeoInfo))

    val locale: String
        get() {
            val localeIdentifier = factory.makeLocaleIdentifier()
            return localeIdentifier ?: Locale.getDefault().toString()
        }

    val appVersion: String
        get() =
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName
            } catch (e: PackageManager.NameNotFoundException) {
                Logger.debug(
                    LogLevel.error,
                    LogScope.device,
                    "DeviceHelper: Failed to load version info - $e",
                )
                ""
            }

    val osVersion: String
        get() = Build.VERSION.RELEASE ?: ""

    val isEmulator: Boolean
        get() = Build.DEVICE.contains("generic") || Build.DEVICE.contains("emulator")

    val model: String
        get() = Build.MODEL

    val vendorId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

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

    val secondsFromGMT: String
        get() = (TimeZone.getDefault().rawOffset / 1000).toString()

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

            val networkCapabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            return when {
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wifi"
                else -> ""
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
        get() {
            val date =
                withErrorTracking<Date> {

                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    return@withErrorTracking Date(packageInfo.firstInstallTime)
                }
            val formatter = dateFormat(DateUtils.SIMPLE)
            return formatter.format(date.getSuccess() ?: Date())
        }

    var interfaceStyleOverride: InterfaceStyle? = null

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

    val isLowPowerModeEnabled: String
        get() {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (powerManager.isPowerSaveMode) "true" else "false"
            } else {
                "false"
            }
        }

    private val localDateFormat: SimpleDateFormat
        get() {
            val formatter = dateFormat(DateUtils.yyyy_MM_dd)
            formatter.timeZone = TimeZone.getDefault()
            return formatter
        }

    private val utcDateFormat: SimpleDateFormat
        get() {
            val formatter = dateFormat(DateUtils.yyyy_MM_dd)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter
        }

    private val utcTimeFormat: SimpleDateFormat
        get() {
            val formatter = dateFormat(DateUtils.HH_mm_ss)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter
        }

    private val localDateTimeFormat: SimpleDateFormat
        get() {
            val formatter = dateFormat(DateUtils.ISO_SECONDS)
            formatter.timeZone = TimeZone.getDefault()
            return formatter
        }

    private val localTimeFormat: SimpleDateFormat
        get() {
            val formatter = dateFormat(DateUtils.HH_mm_ss)
            formatter.timeZone = TimeZone.getDefault()
            return formatter
        }

    private val utcDateTimeFormat: SimpleDateFormat
        get() {
            val formatter = dateFormat(DateUtils.ISO_SECONDS)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter
        }

    private val localDateString: String
        get() = localDateFormat.format(System.currentTimeMillis())

    private val localTimeString: String
        get() = localTimeFormat.format(System.currentTimeMillis())

    private val localDateTimeString: String
        get() = localDateTimeFormat.format(System.currentTimeMillis())

    private val utcDateString: String
        get() = utcDateFormat.format(System.currentTimeMillis())

    private val utcTimeString: String
        get() = utcTimeFormat.format(System.currentTimeMillis())

    private val utcDateTimeString: String
        get() = utcDateTimeFormat.format(System.currentTimeMillis())

    private val sdkVersionPadded: String
        get() {
            // Separate out the "beta" part from the main version.
            val components = sdkVersion.split("-")
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
                        String.format("%03d", appendixComponents[1].toIntOrNull() ?: 0)
                    appendix += ".$appendixVersion"
                }
            }

            // Separate out the version numbers.
            val versionComponents = versionNumber.split(".")
            var newVersion = ""
            if (versionComponents.isNotEmpty()) {
                val major = String.format("%03d", versionComponents[0].toIntOrNull() ?: 0)
                newVersion += major
            }
            if (versionComponents.size > 1) {
                val minor = String.format("%03d", versionComponents[1].toIntOrNull() ?: 0)
                newVersion += ".$minor"
            }
            if (versionComponents.size > 2) {
                val patch = String.format("%03d", versionComponents[2].toIntOrNull() ?: 0)
                newVersion += ".$patch"
            }

            newVersion += appendix

            return newVersion
        }

    val appBuildString: String
        get() {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return packageInfo.versionCode.toString()
        }

    val sdkVersion: String
        get() = BuildConfig.SDK_VERSION

    val buildTime: String?
        get() = BuildConfig.BUILD_TIME

    val gitSha: String?
        get() = BuildConfig.GIT_SHA

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

    suspend fun getTemplateDevice(): Map<String, Any> {
        return withErrorTracking {
            val identityInfo = factory.makeIdentityInfo()
            val aliases = listOf(identityInfo.aliasId)
            val geo =
                try {
                    withTimeout(1.minutes) {
                        lastGeoInfo.first { it != null }
                    }
                } catch (e: Throwable) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.device,
                        message = "Failed to get geo info - timeout",
                        info = emptyMap(),
                        error = e,
                    )
                    null
                }
            val capabilities: List<Capability> =
                listOf(
                    Capability.PaywallEventReceiver(),
                    Capability.MultiplePaywallUrls,
                    Capability.ConfigCaching,
                )

            DeviceTemplate(
                publicApiKey = storage.apiKey,
                platform = "Android",
                appUserId = identityInfo.appUserId ?: "",
                aliases = aliases,
                vendorId = vendorId,
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
                isLowPowerModeEnabled = isLowPowerModeEnabled.toBoolean(),
                bundleId = bundleId,
                appInstallDate = appInstalledAtString,
                isMac = false,
                daysSinceInstall = daysSinceInstall,
                minutesSinceInstall = minutesSinceInstall,
                daysSinceLastPaywallView = daysSinceLastPaywallView,
                minutesSinceLastPaywallView = minutesSinceLastPaywallView,
                totalPaywallViews = totalPaywallViews,
                utcDate = utcDateString,
                localDate = localDateString,
                utcTime = utcTimeString,
                localTime = localTimeString,
                utcDateTime = utcDateTimeString,
                localDateTime = localDateTimeString,
                isSandbox = isSandbox.toString(),
                subscriptionStatus =
                    Superwall.instance.subscriptionStatus.value
                        .toString(),
                isFirstAppOpen = isFirstAppOpen,
                sdkVersion = sdkVersion,
                sdkVersionPadded = sdkVersionPadded,
                appBuildString = appBuildString,
                appBuildStringNumber = appBuildString.toInt(),
                interfaceStyleMode = if (interfaceStyleOverride == null) "automatic" else "manual",
                ipRegion = geo?.region,
                ipRegionCode = geo?.regionCode,
                ipCountry = geo?.country,
                ipCity = geo?.city,
                ipContinent = geo?.continent,
                ipTimezone = geo?.timezone,
                capabilities = capabilities.map { it.name },
                capabilitiesConfig =
                    capabilities.toJson(factory.json()),
                platformWrapper = platformWrapper,
                platformWrapperVersion = platformWrapperVersion,
            )
        }.toResult().fold(
            onSuccess = { deviceTemplate ->
                return@fold deviceTemplate.toDictionary()
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

    suspend fun getGeoInfo() =
        network
            .getGeoInfo()
            .then {
                lastGeoInfo.value = it
            }
}
