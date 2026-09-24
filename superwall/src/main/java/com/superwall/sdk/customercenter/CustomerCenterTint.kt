package com.superwall.sdk.customercenter

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat

/**
 * The colour the Customer Center tints its rows, buttons and spinners with.
 *
 * Like iOS, where the Customer Center inherits the host app's tint, it takes the host app's own
 * colour unless one is configured: the configured accent first, then the `colorPrimary` of the
 * theme the app presented it from, then a neutral system blue.
 */
internal object CustomerCenterTint {
    /** iOS's system blue, the colour a tint falls back to when the app gives none. */
    const val FALLBACK_LIGHT = 0xFF007AFF.toInt()
    const val FALLBACK_DARK = 0xFF0A84FF.toInt()

    /**
     * Prefixes of the colour resources that Material Components and AppCompat use for their own
     * defaults. A theme resolving `colorPrimary` to one of these hasn't chosen a colour — it's
     * showing the library's — so it isn't the app's tint. Library resources are merged into the
     * app's package, so the package can't tell them apart; their names can.
     */
    private val libraryColorPrefixes =
        listOf("m3_", "material_", "mtrl_", "design_", "abc_", "primary_material_", "accent_material_")

    fun resolve(
        configured: Int?,
        host: Int?,
        isDark: Boolean,
    ): Int = configured ?: host ?: if (isDark) FALLBACK_DARK else FALLBACK_LIGHT

    /**
     * The theme of the activity the Customer Center is presented from, else the application's.
     * `0` when neither declares one. A theme an activity sets at runtime with `setTheme` isn't
     * visible from outside it, so it's missed here and the manifest's is used instead.
     */
    fun hostThemeResId(
        context: Context,
        activity: Activity?,
    ): Int {
        val fromActivity =
            activity?.let {
                try {
                    it.packageManager.getActivityInfo(it.componentName, 0).themeResource
                } catch (e: PackageManager.NameNotFoundException) {
                    0
                }
            } ?: 0
        return if (fromActivity != 0) fromActivity else context.applicationInfo.theme
    }

    /**
     * The `colorPrimary` the host theme [themeResId] declares, resolved for [context]'s current
     * light/dark mode, or `null` when the app left it at a framework or library default.
     */
    fun hostColor(
        context: Context,
        themeResId: Int,
    ): Int? {
        if (themeResId == 0) return null
        val themed = ContextThemeWrapper(context, themeResId)
        val attrs = listOf(androidx.appcompat.R.attr.colorPrimary, android.R.attr.colorPrimary)
        for (attr in attrs) {
            val value = TypedValue()
            if (!themed.theme.resolveAttribute(attr, value, true)) continue
            if (value.resourceId != 0 && isDefaultColor(themed.resources, value.resourceId)) continue
            if (value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
                if (value.resourceId == 0) continue
                return runCatching { ContextCompat.getColor(themed, value.resourceId) }.getOrNull() ?: continue
            }
            return value.data
        }
        return null
    }

    private fun isDefaultColor(
        resources: Resources,
        resId: Int,
    ): Boolean {
        val packageName = runCatching { resources.getResourcePackageName(resId) }.getOrNull() ?: return false
        if (packageName == "android") return true
        val name = runCatching { resources.getResourceEntryName(resId) }.getOrNull() ?: return false
        return isLibraryColorName(name)
    }

    fun isLibraryColorName(name: String): Boolean = libraryColorPrefixes.any(name::startsWith)
}
