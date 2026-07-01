package com.superwall.sdk.config.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class EventTrackingBehaviorTest {
    @Test
    fun `default event tracking behavior is ALL`() {
        val options = SuperwallOptions()
        assertEquals(EventTrackingBehavior.ALL, options.eventTrackingBehavior)
    }

    @Test
    fun `descriptions match the wire format`() {
        assertEquals("all", EventTrackingBehavior.ALL.description)
        assertEquals("superwallOnly", EventTrackingBehavior.SUPERWALL_ONLY.description)
        assertEquals("none", EventTrackingBehavior.NONE.description)
    }

    @Test
    fun `deprecated flag getter reflects the behavior`() {
        val options = SuperwallOptions()
        assertTrue(options.isExternalDataCollectionEnabled)

        options.eventTrackingBehavior = EventTrackingBehavior.SUPERWALL_ONLY
        assertFalse(options.isExternalDataCollectionEnabled)

        options.eventTrackingBehavior = EventTrackingBehavior.NONE
        assertFalse(options.isExternalDataCollectionEnabled)
    }

    @Test
    fun `setting deprecated flag to false maps to superwallOnly`() {
        val options = SuperwallOptions()
        options.isExternalDataCollectionEnabled = false
        assertEquals(EventTrackingBehavior.SUPERWALL_ONLY, options.eventTrackingBehavior)
    }

    @Test
    fun `setting deprecated flag to false preserves none`() {
        val options = SuperwallOptions()
        options.eventTrackingBehavior = EventTrackingBehavior.NONE
        options.isExternalDataCollectionEnabled = false
        assertEquals(EventTrackingBehavior.NONE, options.eventTrackingBehavior)
    }

    @Test
    fun `setting deprecated flag to true maps to all`() {
        val options = SuperwallOptions()
        options.eventTrackingBehavior = EventTrackingBehavior.NONE
        options.isExternalDataCollectionEnabled = true
        assertEquals(EventTrackingBehavior.ALL, options.eventTrackingBehavior)
    }

    @Test
    fun `toMap encodes the behavior and the legacy flag`() {
        val options = SuperwallOptions()

        val allMap = options.toMap()
        assertEquals("all", allMap["event_tracking_behavior"])
        assertEquals(true, allMap["is_external_data_collection_enabled"])

        options.eventTrackingBehavior = EventTrackingBehavior.SUPERWALL_ONLY
        val superwallOnlyMap = options.toMap()
        assertEquals("superwallOnly", superwallOnlyMap["event_tracking_behavior"])
        assertEquals(false, superwallOnlyMap["is_external_data_collection_enabled"])

        options.eventTrackingBehavior = EventTrackingBehavior.NONE
        val noneMap = options.toMap()
        assertEquals("none", noneMap["event_tracking_behavior"])
        assertEquals(false, noneMap["is_external_data_collection_enabled"])
    }
}
