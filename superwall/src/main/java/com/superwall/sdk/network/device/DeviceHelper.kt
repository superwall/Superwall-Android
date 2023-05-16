package com.superwall.sdk.network.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

class DeviceHelper(private val context: Context) {

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

    val isLowPowerModeEnabled: Boolean
        get() = powerManager.isPowerSaveMode

    val bundleId: String
        get() = context.packageName

    // Android doesn't have an equivalent for iOS's sandbox or TestFlight

    val urlScheme: String
        get() = context.packageName

    val appInstallDateString: String
        get() {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installDate = Date(packageInfo.firstInstallTime)
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return formatter.format(installDate)
        }


    // You'll need to define your own method for these since there's no direct equivalent in Android:
    // daysSinceLastPaywallView, minutesSinceLastPaywallView, totalPaywallViews

    // TODO: Add these methods to the DeviceHelper class

}
