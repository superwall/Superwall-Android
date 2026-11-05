package com.superwall.sdk.customercenter

import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import java.net.URI

/**
 * Configures the screens, actions, support options and appearance of the Customer Center.
 *
 * Set the default via [com.superwall.sdk.config.options.SuperwallOptions.customerCenter] before
 * calling `configure`, or pass one to
 * [com.superwall.sdk.Superwall.presentCustomerCenter].
 */
data class CustomerCenterConfiguration
    @JvmOverloads
    constructor(
        /** The screen shown when the user has at least one subscription (active or expired) or purchase. */
        val managementScreen: Screen,
        /** The screen shown when the user has no purchases at all. */
        val noPurchasesScreen: Screen,
        /** Support-related settings (email, app update warning, web management URL). */
        val support: Support = Support(),
        /** Optional color overrides. `null` values use the default theme colors. */
        val appearance: Appearance = Appearance(),
        /** Shows the account details section (user ID, original download date). Defaults to `true`. */
        val showsAccountDetails: Boolean = true,
        /** Warns when both a Google Play and a web subscription are active. Defaults to `true`. */
        val warnsAboutDuplicateSubscriptions: Boolean = true,
    ) {
        /** A Customer Center screen: a title, optional subtitle and an ordered list of paths. */
        data class Screen
            @JvmOverloads
            constructor(
                /** Title. `null` uses the localized default for the screen. */
                val title: String? = null,
                /** Subtitle. `null` uses the localized default (no-purchases screen) or none (management screen). */
                val subtitle: String? = null,
                /** Ordered paths (actions) shown on the screen. */
                val paths: List<Path>,
            )

        /** An action row in the Customer Center. */
        data class Path
            @JvmOverloads
            constructor(
                /** What the path does. */
                val type: PathType,
                /** Row title. `null` uses the localized default for [type]. */
                val title: String? = null,
                /** Optional survey shown before the action runs. */
                val survey: FeedbackSurvey? = null,
                /**
                 * Stable identifier, reported as `path_id` on Customer Center events. Defaults to
                 * [PathType.defaultId]. Only needed to tell apart two paths that would otherwise
                 * share one, such as two paths of the same built-in type.
                 */
                val id: String = type.defaultId,
            ) {
            /**
             * Lets a screen list its paths as `Path.restore()`, `Path.refund(window = 86_400_000)`,
             * `Path.url("https://…", title = "FAQ")` and so on, rather than spelling out
             * `Path(type = …)` each time.
             */
            companion object {
                @JvmStatic
                @JvmOverloads
                fun restore(
                    id: String? = null,
                    title: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.Restore, id, title, survey)

                @JvmStatic
                @JvmOverloads
                fun manageSubscription(
                    id: String? = null,
                    title: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.ManageSubscription, id, title, survey)

                /** @param windowMillis Milliseconds since purchase during which a refund may be requested. */
                @JvmStatic
                @JvmOverloads
                fun refund(
                    windowMillis: Long? = null,
                    id: String? = null,
                    title: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.Refund(windowMillis), id, title, survey)

                /** @param productIds The subset of plans to offer. `null` offers every plan. */
                @JvmStatic
                @JvmOverloads
                fun changePlan(
                    productIds: List<String>? = null,
                    id: String? = null,
                    title: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.ChangePlan(productIds), id, title, survey)

                @JvmStatic
                @JvmOverloads
                fun contactSupport(
                    id: String? = null,
                    title: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.ContactSupport, id, title, survey)

                /**
                 * @param title What the row says. Required: a URL has no name the SDK could give it.
                 * @param openMethod Opens in an in-app browser tab by default.
                 */
                @JvmStatic
                @JvmOverloads
                fun url(
                    url: String,
                    title: String,
                    openMethod: OpenMethod = OpenMethod.IN_APP,
                    id: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.Url(url, openMethod), id, title, survey)

                /** @param identifier Passed back in [CustomerCenterAction.Custom] when tapped. */
                @JvmStatic
                @JvmOverloads
                fun custom(
                    identifier: String,
                    title: String? = null,
                    id: String? = null,
                    survey: FeedbackSurvey? = null,
                ): Path = make(PathType.Custom(identifier), id, title, survey)

                private fun make(
                    type: PathType,
                    id: String?,
                    title: String?,
                    survey: FeedbackSurvey?,
                ): Path = Path(type = type, title = title, survey = survey, id = id ?: type.defaultId)
            }
        }

        /** The kinds of path the Customer Center supports. */
        sealed class PathType {
            object Restore : PathType() {
                override fun toString() = "Restore"
            }

            object ManageSubscription : PathType() {
                override fun toString() = "ManageSubscription"
            }

            /** @property windowMillis Optional milliseconds since purchase during which a refund may be requested. */
            data class Refund(
                val windowMillis: Long? = null,
            ) : PathType()

            /** @property productIds Optional subset of plans to offer. `null` offers every plan. */
            data class ChangePlan(
                val productIds: List<String>? = null,
            ) : PathType()

            object ContactSupport : PathType() {
                override fun toString() = "ContactSupport"
            }

            /**
             * Opens a URL. Unlike the other types, a URL has no name the SDK can give it, so set the
             * row's title on [Path.title] — the [Path.url] shorthand requires one. Without a title
             * the row shows the URL's host, which is the same for every link to one site, so FAQ,
             * terms and privacy would read identically.
             */
            data class Url(
                val url: String,
                val openMethod: OpenMethod = OpenMethod.IN_APP,
            ) : PathType()

            data class Custom(
                val identifier: String,
            ) : PathType()

            /**
             * The ID a path gets when none is given: the type's name for the built-in types, the
             * URL's host and path for a URL path, and the identifier for a custom path.
             *
             * A URL path leaves out the query and fragment because the ID is reported in analytics,
             * and a query can carry a token.
             */
            val defaultId: String
                get() =
                    when (this) {
                        Restore -> "restore"
                        ManageSubscription -> "manage_subscription"
                        is Refund -> "refund"
                        is ChangePlan -> "change_plan"
                        ContactSupport -> "contact_support"
                        is Url -> CustomerCenterUrls.hostAndPath(url)
                        is Custom -> identifier
                    }
        }

        /** How a URL path opens. */
        enum class OpenMethod {
            /** In an in-app browser tab (Custom Tabs), when the URL is `http` or `https`. */
            IN_APP,

            /** Handed to whichever app handles the URL. */
            EXTERNAL,
        }

        /** A single-choice survey shown before a path's action runs. */
        data class FeedbackSurvey
            @JvmOverloads
            constructor(
                val id: String,
                /**
                 * Question text. `null` uses the localized "Why are you cancelling?" on a
                 * manage-subscription path, and no title on any other path.
                 */
                val title: String? = null,
                val options: List<Option>,
            ) {
                data class Option
                    @JvmOverloads
                    constructor(
                        val id: String,
                        /**
                         * Option text. `null` uses the localized default for the built-in options
                         * ([tooExpensive], [dontUse] and [boughtByMistake]).
                         */
                        val title: String? = null,
                    ) {
                        companion object {
                            /** "Too expensive", localized. */
                            @JvmStatic
                            val tooExpensive: Option get() = Option("too_expensive")

                            /** "Don't use the app", localized. */
                            @JvmStatic
                            val dontUse: Option get() = Option("dont_use")

                            /** "Bought by mistake", localized. */
                            @JvmStatic
                            val boughtByMistake: Option get() = Option("bought_by_mistake")
                        }
                    }

                companion object {
                    /**
                     * The built-in cancellation survey, as used by the default configuration: "Why
                     * are you cancelling?" with the three built-in options, all localized.
                     */
                    @JvmStatic
                    val cancellation: FeedbackSurvey
                        get() =
                            FeedbackSurvey(
                                id = "cancel_survey",
                                options = listOf(Option.tooExpensive, Option.dontUse, Option.boughtByMistake),
                            )
                }
            }

        data class Support
            @JvmOverloads
            constructor(
                /** Support email for the "Contact support" path. `null` hides that path. */
                val email: String? = null,
                /**
                 * Latest published app version. When set and newer than the installed version, an
                 * update banner shows.
                 */
                val latestAppVersion: String? = null,
                /** Whether to show the update banner. Defaults to `true`. */
                val warnsAboutUpdates: Boolean = true,
                /** Overrides the web subscription management page URL used for web-store subscriptions. */
                val webManagementUrl: String? = null,
            )

        /**
         * Colour overrides for the Customer Center.
         *
         * Only the accent is applied. Slots for other colours come back when they're wired up.
         */
        data class Appearance
            @JvmOverloads
            constructor(
                /** Tints buttons and links. `null` uses the theme's accent. */
                val accent: ColorPair? = null,
            ) {
                /** A light/dark colour pair stored as hex strings (`#RRGGBB` or `#RRGGBBAA`). */
                data class ColorPair(
                    val light: String,
                    val dark: String,
                )
            }

        companion object {
            /**
             * A fresh copy of the default configuration: restore, change plan, refund, manage
             * subscription (with a cancellation survey) and contact support on the management
             * screen; restore on the no-purchases screen.
             */
            @JvmStatic
            val default: CustomerCenterConfiguration
                get() =
                    CustomerCenterConfiguration(
                        managementScreen =
                            Screen(
                                paths =
                                    listOf(
                                        Path.restore(),
                                        Path.changePlan(),
                                        Path.refund(),
                                        Path.manageSubscription(survey = FeedbackSurvey.cancellation),
                                        Path.contactSupport(),
                                    ),
                            ),
                        noPurchasesScreen = Screen(paths = listOf(Path.restore())),
                    )
        }
    }

// region Configuration checks

/** Path IDs that appear more than once on the same screen. */
internal val CustomerCenterConfiguration.duplicatePathIds: List<String>
    get() =
        listOf(managementScreen, noPurchasesScreen).flatMap { screen ->
            screen.paths
                .groupBy { it.id }
                .filterValues { it.size > 1 }
                .keys
                .sorted()
        }

/** IDs of URL paths with no title, which fall back to showing the URL's host. */
internal val CustomerCenterConfiguration.untitledUrlPathIds: List<String>
    get() =
        (managementScreen.paths + noPurchasesScreen.paths)
            .filter { it.type is CustomerCenterConfiguration.PathType.Url && it.title == null }
            .map { it.id }

/**
 * IDs of paths whose survey has no title and so would show none: only a manage-subscription path
 * has a default question.
 */
internal val CustomerCenterConfiguration.untitledSurveyPathIds: List<String>
    get() =
        (managementScreen.paths + noPurchasesScreen.paths)
            .filter {
                it.survey != null &&
                    it.survey.title == null &&
                    it.type != CustomerCenterConfiguration.PathType.ManageSubscription
            }.map { it.id }

/** Accent colour strings that aren't valid `#RRGGBB` or `#RRGGBBAA` hex, and so are ignored. */
internal val CustomerCenterConfiguration.invalidAccentHexes: List<String>
    get() {
        val accent = appearance.accent ?: return emptyList()
        return listOf(accent.light, accent.dark).filter { CustomerCenterColors.parseHex(it) == null }
    }

/** Logs configuration mistakes that still render, just badly. */
internal fun CustomerCenterConfiguration.warnAboutConfigurationProblems() {
    fun warn(message: String) = Logger.debug(LogLevel.warn, LogScope.customerCenter, message)

    duplicatePathIds.takeIf { it.isNotEmpty() }?.let {
        warn(
            "Customer Center paths share an id on the same screen: ${it.joinToString()}. " +
                "Pass a distinct `id` to each.",
        )
    }
    untitledUrlPathIds.takeIf { it.isNotEmpty() }?.let {
        warn(
            "Customer Center URL paths have no title and will show the URL's host: " +
                "${it.joinToString()}. Give each a `title`.",
        )
    }
    untitledSurveyPathIds.takeIf { it.isNotEmpty() }?.let {
        warn(
            "Customer Center surveys on these paths have no title, so they'll show no question: " +
                "${it.joinToString()}. Give each survey a `title`.",
        )
    }
    invalidAccentHexes.takeIf { it.isNotEmpty() }?.let {
        warn(
            "Customer Center accent colours aren't valid hex and will be ignored: " +
                "${it.joinToString()}. Use #RRGGBB or #RRGGBBAA.",
        )
    }
}

// endregion

internal object CustomerCenterUrls {
    /**
     * The URL with any query, fragment and user info removed, for reporting: any of them can
     * carry a token.
     */
    fun withoutQueryOrFragment(url: String): String =
        try {
            val uri = URI(url)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        } catch (e: Exception) {
            url.substringBefore('#').substringBefore('?')
        }

    /** Host and path, as in `app.com/faq`, or the whole URL when it has neither. */
    fun hostAndPath(url: String): String {
        val value =
            try {
                val uri = URI(url)
                (uri.host ?: "") + (uri.path ?: "")
            } catch (e: Exception) {
                ""
            }
        return value.ifEmpty { withoutQueryOrFragment(url) }
    }

    fun host(url: String): String? =
        try {
            URI(url).host
        } catch (e: Exception) {
            null
        }

    fun scheme(url: String): String? =
        try {
            URI(url).scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
}

internal object CustomerCenterColors {
    /** Parses `#RRGGBB` / `#RRGGBBAA` / `RRGGBB` into an Android ARGB colour int. */
    fun parseHex(hex: String): Int? {
        val value = hex.trim().removePrefix("#")
        if (value.length != 6 && value.length != 8) return null
        val int = value.toLongOrNull(16) ?: return null
        return if (value.length == 8) {
            val rgb = (int shr 8) and 0xFFFFFF
            val alpha = int and 0xFF
            ((alpha shl 24) or rgb).toInt()
        } else {
            (0xFF000000 or int).toInt()
        }
    }
}
