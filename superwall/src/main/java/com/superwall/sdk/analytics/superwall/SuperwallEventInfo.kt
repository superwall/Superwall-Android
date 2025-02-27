package com.superwall.sdk.analytics.superwall

data class SuperwallEventInfo(
    public val placement: SuperwallPlacement,
    public val params: Map<String, Any>,
)
