package com.superwall.sdk.web

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import com.superwall.sdk.Superwall
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.models.config.Config

fun Superwall.openRestoreOnWeb() {
    openRestoreOnWeb(
        config = dependencyContainer.configManager.config,
        activityProvider = dependencyContainer.activityProvider,
    )
}

/** Opens the web restore page from [config] in the current activity, if both exist. */
internal fun openRestoreOnWeb(
    config: Config?,
    activityProvider: ActivityProvider?,
) {
    val webToAppConfig =
        config?.webToAppConfig ?: run {
            Logger.debug(
                LogLevel.error,
                LogScope.webEntitlements,
                "Configuration not available - cannot restore purchases",
            )
            return
        }

    activityProvider?.getCurrentActivity()?.let {
        it.startActivity(
            Intent(
                ACTION_VIEW,
                Uri.parse(
                    webToAppConfig.restoreAccesUrl,
                ),
            ),
        )
    }
}
