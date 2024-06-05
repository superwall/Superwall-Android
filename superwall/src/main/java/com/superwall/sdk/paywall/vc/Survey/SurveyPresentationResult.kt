package com.superwall.sdk.paywall.vc.Survey

enum class SurveyPresentationResult(
    val rawValue: String,
) {
    SHOW("show"),
    HOLDOUT("holdout"),
    NOSHOW("noShow"),
}
