package com.superwall.sdk.analytics.superwall

data class SuperwallEventInfo(
    public val event: SuperwallEvent,
    public val params: Map<String, Any>,
)
