package com.superwall.sdk.network.device

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceIPCollectorTest {
    @Test
    fun preservesBothFamiliesAndExpiresObservations() = runTest {
        var time = Instant.parse("2026-09-15T21:00:00.000Z").toEpochMilli()
        val collector = DeviceIPCollector(scope = this, now = { time }, fetch = { emptyMap() })
        val timestamp = "2026-09-15T21:00:00.000Z"
        collector.record(mapOf("ipAddress" to "8.8.8.8", "ipAddressObservedAt" to timestamp))
        collector.record(mapOf("ipV6" to "2001:db8::1", "ipV6ObservedAt" to timestamp))
        collector.record(mapOf("ipV4" to "1.1.1.1", "ipV4ObservedAt" to "2000-01-01T00:00:00.000Z"))
        collector.record(mapOf("ipV6" to "invalid", "ipV6ObservedAt" to timestamp))
        assertEquals("8.8.8.8", collector.attributes()["ipV4"])
        assertEquals("2001:db8::1", collector.attributes()["ipV6"])
        time += 15 * 60 * 1000
        assertTrue(collector.attributes().isEmpty())
    }

    @Test
    fun coalescesRefreshesAndToleratesFailure() = runTest {
        var calls = 0
        val collector = DeviceIPCollector(scope = this, fetch = { calls++; error("offline") })
        collector.refreshIfNeeded()
        collector.refreshIfNeeded()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(collector.attributes().isEmpty())
    }

    @Test
    fun validatesNumericFamilies() {
        assertTrue(DeviceIPCollector.isValid("8.8.8.8", 4))
        assertFalse(DeviceIPCollector.isValid("999.8.8.8", 4))
        assertFalse(DeviceIPCollector.isValid("example.com", 6))
        assertFalse(DeviceIPCollector.isValid("::ffff:8.8.8.8", 6))
    }
}
