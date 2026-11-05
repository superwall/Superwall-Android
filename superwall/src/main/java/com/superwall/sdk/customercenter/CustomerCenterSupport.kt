package com.superwall.sdk.customercenter

import java.net.URLEncoder

internal data class SupportEmailDiagnostics(
    val userId: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String,
    val sdkVersion: String,
    val activeEntitlementIds: List<String>,
    val isSandbox: Boolean,
)

internal object SupportEmailComposer {
    fun mailtoUrl(
        email: String?,
        subject: String,
        body: String,
        diagnostics: SupportEmailDiagnostics,
    ): String? {
        val address = email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val entitlements =
            diagnostics.activeEntitlementIds
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") ?: "none"
        val fullBody =
            """
            |$body
            |
            |---------------------------
            |- User ID: ${diagnostics.userId}
            |- App Version: ${diagnostics.appVersion}
            |- OS Version: ${diagnostics.osVersion}
            |- Device: ${diagnostics.deviceModel}
            |- SDK Version: ${diagnostics.sdkVersion}
            |- Entitlements: $entitlements
            |- Sandbox: ${diagnostics.isSandbox}
            """.trimMargin()
        return "mailto:${encode(address).replace("%40", "@")}?subject=${encode(subject)}&body=${encode(fullBody)}"
    }

    /** Percent-encodes for a `mailto:` URL, where a space must be `%20` rather than `+`. */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Compares marketing version strings on up to three leading numeric components. */
internal object AppVersionComparator {
    /** Returns `true` only when both strings parse and [installed] < [latest]. */
    fun isInstalledVersionOlder(
        installed: String?,
        latest: String?,
    ): Boolean {
        val installedParts = parse(installed) ?: return false
        val latestParts = parse(latest) ?: return false
        for (i in 0 until 3) {
            if (installedParts[i] != latestParts[i]) return installedParts[i] < latestParts[i]
        }
        return false
    }

    /** Parses "1.2.3", "1.2", "1" → [major, minor, patch]; returns null if the first component isn't numeric. */
    fun parse(version: String?): List<Int>? {
        if (version == null) return null
        val parts = version.split(".").take(3).map { it.toIntOrNull() }
        if (parts.firstOrNull() == null) return null
        return parts.map { it ?: 0 } + List(3 - parts.size) { 0 }
    }
}

internal object WebManagementUrlResolver {
    /**
     * The page a web-store customer manages their subscription on: the host's override if set,
     * else Superwall's management page derived from the web restore URL.
     */
    fun resolve(
        override: String?,
        restoreAccessUrl: String?,
    ): String? {
        if (!override.isNullOrBlank()) return override
        if (restoreAccessUrl.isNullOrBlank()) return null
        return try {
            val uri = java.net.URI(restoreAccessUrl)
            val host = uri.host ?: return restoreAccessUrl
            if (host != "superwall.app" && !host.endsWith(".superwall.app")) return restoreAccessUrl
            java.net.URI(uri.scheme, null, host, uri.port, "/manage", null, null).toString()
        } catch (e: Exception) {
            restoreAccessUrl
        }
    }
}
