package com.superwall.sdk.analytics.superwall

import org.junit.Test

class SuperwallPlacementTest {
    @Test
    fun test_app_install() {
        val event = SuperwallPlacement.AppInstall()
        assert(event.backingEvent == SuperwallEvents.AppInstall)
    }
}
