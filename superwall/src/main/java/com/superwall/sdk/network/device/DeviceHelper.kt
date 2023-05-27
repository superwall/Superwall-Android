package com.superwall.sdk.network.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.superwall.sdk.Superwall
import com.superwall.sdk.dependencies.IdentityInfoAndLocaleIdentifierFactory
import com.superwall.sdk.dependencies.IdentityInfoFactory
import com.superwall.sdk.dependencies.LocaleIdentifierFactory
import com.superwall.sdk.paywall.view_controller.web_view.templating.models.DeviceTemplate
import com.superwall.sdk.storage.Storage
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap


class DeviceHelper(private val context: Context, val storage: Storage, val factory: IdentityInfoAndLocaleIdentifierFactory) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val packageManager = context.packageManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    private val installTime = Date(appInfo.firstInstallTime)
    private val installTimeFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val installTimeString = installTimeFormatter.format(installTime)
    private val daysSinceInstall = ((Date().time - installTime.time) / (1000 * 60 * 60 * 24)).toInt()
    private val minutesSinceInstall = ((Date().time - installTime.time) / (1000 * 60)).toInt()

    val locale: String
        get() = Locale.getDefault().toString()

    val appVersion: String
        get() = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("DeviceHelper", "Failed to load version info", e)
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

    val languageCode: String
        get() = Locale.getDefault().language

    val currencyCode: String
        get() = Currency.getInstance(Locale.getDefault()).currencyCode

    val currencySymbol: String
        get() = Currency.getInstance(Locale.getDefault()).symbol

    val secondsFromGMT: String
        get() = (TimeZone.getDefault().rawOffset / 1000).toString()

    val radioType: String
        @SuppressLint("MissingPermission")
        get() {

            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
                return ""
            }

            val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            return when {
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wifi"
                else -> ""
            }
        }



    val bundleId: String
        get() = context.packageName

    // Android doesn't have an equivalent for iOS's sandbox or TestFlight

    val urlScheme: String
        get() = context.packageName

    val appInstalledAtString: String
        get() {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installDate = Date(packageInfo.firstInstallTime)
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return formatter.format(installDate)
        }



    val interfaceStyle: String
        get() {
            val style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formatter.timeZone = TimeZone.getDefault()
            return formatter
        }

    private val utcDateFormat: SimpleDateFormat
        get() {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter
        }

    private val utcTimeFormat: SimpleDateFormat
        get() {
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter
        }

    private val localDateTimeFormat: SimpleDateFormat
        get() {
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            formatter.timeZone = TimeZone.getDefault()
            return formatter
        }

    private val localTimeFormat: SimpleDateFormat
        get() {
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
            formatter.timeZone = TimeZone.getDefault()
            return formatter
        }

    private val utcDateTimeFormat: SimpleDateFormat
        get() {
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
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


    // You'll need to define your own method for these since there's no direct equivalent in Android:
    // daysSinceLastPaywallView, minutesSinceLastPaywallView, totalPaywallViews

    // TODO: Add these methods to the DeviceHelper class
    suspend fun getTemplateDevice(): DeviceTemplate {
        val identityInfo =  factory.makeIdentityInfo()
        val aliases = listOf(identityInfo.aliasId)

        return DeviceTemplate(
            publicApiKey = storage.apiKey,
            platform = "Android",
            appUserId = identityInfo.appUserId ?: "",
            aliases = aliases,
            vendorId = vendorId,
            appVersion = appVersion,
            osVersion = osVersion,
            deviceModel = model,
            deviceLocale = locale,
            deviceLanguageCode = languageCode,
            deviceCurrencyCode = currencyCode,
            deviceCurrencySymbol = currencySymbol,
            timezoneOffset = Date().timezoneOffset,
            radioType = radioType,
            interfaceStyle = interfaceStyle,
            isLowPowerModeEnabled = isLowPowerModeEnabled.toBoolean(),
            bundleId = bundleId,
            appInstallDate = appInstalledAtString,
            isMac = false,
            daysSinceInstall = daysSinceInstall,
            minutesSinceInstall = minutesSinceInstall,

            // TODO: Fix these with actual values
            daysSinceLastPaywallView = 0,
            minutesSinceLastPaywallView = 0,
            totalPaywallViews = 0,

//            daysSinceLastPaywallView = daysSinceLastPaywallView,
//            minutesSinceLastPaywallView = minutesSinceLastPaywallView,
//            totalPaywallViews = totalPaywallViews,

            utcDate = utcDateString,
            localDate = localDateString,
            utcTime = utcTimeString,
            localTime = localTimeString,
            utcDateTime = utcDateTimeString,
            localDateTime = localDateTimeString,
            isSandbox = "false",

            // TODO: Fix these with actual values
            subscriptionStatus = "NOT_SUBSCRIBED",
            isFirstAppOpen = false ,

//            subscriptionStatus = Superwall.instance.subscriptionStatus.description,
//            isFirstAppOpen = isFirstAppOpen
        )
    }


}
