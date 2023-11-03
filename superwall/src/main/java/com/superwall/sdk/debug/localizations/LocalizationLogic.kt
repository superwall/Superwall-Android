package com.superwall.sdk.debug.localizations

import java.util.Locale

object LocalizationLogic {
    fun getSortedLocalizations(
        localeIds: List<String>,
        popularLocales: List<String>
    ): List<LocalizationOption> {
        val localizations = mutableListOf<LocalizationOption>()
        // TODO: Will this have an issue?:
        val currentLocale = Locale.getDefault()

        for (localeId in localeIds) {
            // Get language
            val localizedLanguage = currentLocale.getDisplayLanguage(Locale(localeId)) ?: continue

            // Get country
            val locale = Locale(localeId)
            val localeIdComponents = localeId.split("_")
            var country: String? = null

            when {
                locale.country.isNotEmpty() -> country = currentLocale.getDisplayCountry(locale)
                localeIdComponents.size > 1 -> country = currentLocale.getDisplayCountry(Locale("", localeIdComponents.last()))
            }

            val localizationOption = LocalizationOption(
                language = localizedLanguage,
                country = country,
                locale = localeId,
                popularLocales = popularLocales
            )
            localizations.add(localizationOption)
        }

        // Sort in ascending manner
        localizations.sortBy { it.sortDescription }
        return localizations
    }

    fun getGroupings(
        localizationOptions: List<LocalizationOption>
    ): List<LocalizationGrouping> {
        val groupings = mutableListOf<LocalizationGrouping>()

        for (localizationOption in localizationOptions) {
            val currentTitle = localizationOption.sectionTitle
            val currentGrouping = groupings.lastOrNull()

            if (currentGrouping?.title != currentTitle) {
                groupings.add(LocalizationGrouping(localizations = mutableListOf(), title = currentTitle))
            }

            groupings.last().localizations.add(localizationOption)
        }

        return groupings
    }
}