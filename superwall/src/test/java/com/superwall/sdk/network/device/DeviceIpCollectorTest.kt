package com.superwall.sdk.network.device

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant
import java.io.IOException

/** Mirrors iOS's `DeviceIPCollector` tests. */
class DeviceIpCollectorTest {
    private var now = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()

    private fun timestamp(offsetMillis: Long = 0) = Instant.ofEpochMilli(now + offsetMillis).toString()

    @Test
    fun `records each family with its own timestamp`() =
        runTest {
            val collector = DeviceIpCollector(emptyList(), backgroundScope, now = { now })

            collector.record(
                mapOf(
                    "ipV4" to "203.0.113.7",
                    "ipV4ObservedAt" to timestamp(),
                    "ipV6" to "2001:db8::1",
                    "ipV6ObservedAt" to timestamp(),
                ),
            )

            assertEquals(
                mapOf(
                    "ipV4" to "203.0.113.7",
                    "ipV4ObservedAt" to timestamp(),
                    "ipV6" to "2001:db8::1",
                    "ipV6ObservedAt" to timestamp(),
                ),
                collector.attributes(),
            )
            assertEquals(setOf("ipV4", "ipV4ObservedAt"), collector.attributes(setOf(4)).keys)
        }

    @Test
    fun `falls back to ipAddress but an ipV6 without its own time is ignored`() =
        runTest {
            val collector = DeviceIpCollector(emptyList(), backgroundScope, now = { now })

            collector.record(
                mapOf(
                    "ipAddress" to "203.0.113.7",
                    "ipAddressObservedAt" to timestamp(),
                    "ipV6" to "2001:db8::1",
                ),
            )

            assertEquals("203.0.113.7", collector.attributes()["ipV4"])
            assertNull(collector.attributes()["ipV6"])
        }

    @Test
    fun `ignores invalid, mapped, stale and older addresses`() =
        runTest {
            val collector = DeviceIpCollector(emptyList(), backgroundScope, now = { now })

            collector.record(mapOf("ipV4" to "999.1.1.1", "ipV4ObservedAt" to timestamp()))
            collector.record(mapOf("ipV6" to "::ffff:203.0.113.7", "ipV6ObservedAt" to timestamp()))
            collector.record(mapOf("ipV4" to "203.0.113.1", "ipV4ObservedAt" to timestamp(-16 * 60_000L)))
            assertTrue(collector.attributes().isEmpty())

            collector.record(mapOf("ipV4" to "203.0.113.2", "ipV4ObservedAt" to timestamp()))
            collector.record(mapOf("ipV4" to "203.0.113.3", "ipV4ObservedAt" to timestamp(-60_000L)))
            assertEquals("203.0.113.2", collector.attributes()["ipV4"])
        }

    @Test
    fun `an observation drops out once it is 15 minutes old`() =
        runTest {
            val collector = DeviceIpCollector(emptyList(), backgroundScope, now = { now })
            collector.record(mapOf("ipV4" to "203.0.113.7", "ipV4ObservedAt" to timestamp()))

            now += 15 * 60_000L

            assertTrue(collector.attributes().isEmpty())
        }

    @Test
    fun `a successful lookup isn't repeated until the observation goes stale`() =
        runTest {
            var fetches = 0
            val lookup =
                DeviceIpCollector.Lookup("IPv4", 4) {
                    fetches += 1
                    mapOf("ipV4" to "203.0.113.7", "ipV4ObservedAt" to timestamp())
                }
            val collector = DeviceIpCollector(listOf(lookup), backgroundScope, now = { now })

            collector.refreshIfNeeded()
            runCurrent()
            assertEquals("203.0.113.7", collector.attributes()["ipV4"])

            assertNull(collector.refreshIfNeeded())
            now += 15 * 60_000L
            collector.refreshIfNeeded()
            runCurrent()
            assertEquals(2, fetches)
        }

    @Test
    fun `a failed lookup is retried after a minute`() =
        runTest {
            var fetches = 0
            val lookup =
                DeviceIpCollector.Lookup("IPv6", 6) {
                    fetches += 1
                    throw IOException("offline")
                }
            val collector = DeviceIpCollector(listOf(lookup), backgroundScope, now = { now })

            collector.refreshIfNeeded()
            runCurrent()
            now += 30_000L
            assertNull(collector.refreshIfNeeded())
            now += 30_000L
            collector.refreshIfNeeded()
            runCurrent()

            assertEquals(2, fetches)
        }

    @Test
    fun `IPv4 is only looked up for AppsFlyer once the device has been on IPv6`() =
        runTest {
            var fetches = 0
            val lookup =
                DeviceIpCollector.Lookup("IPv4", 4) {
                    fetches += 1
                    mapOf("ipV4" to "203.0.113.7", "ipV4ObservedAt" to timestamp())
                }
            val collector = DeviceIpCollector(listOf(lookup), backgroundScope, now = { now })

            assertNull(collector.refreshIpV4IfOnlyIpV6IsKnown())

            collector.record(mapOf("ipV6" to "2001:db8::1", "ipV6ObservedAt" to timestamp()))
            collector.refreshIpV4IfOnlyIpV6IsKnown()
            runCurrent()
            assertEquals(1, fetches)

            // A fresh IPv4 address is now known.
            assertNull(collector.refreshIpV4IfOnlyIpV6IsKnown())
        }

    @Test
    fun `reads the body of a 200 response and rejects anything else`() {
        val ok = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"device\":{}}"
        assertEquals("{\"device\":{}}", DeviceIpCollector.bodyOfHttpResponse(ok.toByteArray()))

        assertThrows(IOException::class.java) {
            DeviceIpCollector.bodyOfHttpResponse("HTTP/1.1 503 Unavailable\r\n\r\n".toByteArray())
        }
        assertThrows(IOException::class.java) {
            DeviceIpCollector.bodyOfHttpResponse("HTTP/1.1 200 OK\r\n".toByteArray())
        }
    }

    @Test
    fun `parses the string values of the device object`() {
        val device =
            DeviceIpCollector.parseDevice(
                """{"device":{"ipV4":"203.0.113.7","ipV4ObservedAt":"2026-10-21T12:00:00.123Z","count":3}}""",
            )

        assertEquals(mapOf("ipV4" to "203.0.113.7", "ipV4ObservedAt" to "2026-10-21T12:00:00.123Z"), device)
        assertThrows(IOException::class.java) { DeviceIpCollector.parseDevice("""{"user":{}}""") }
    }

    @Test
    fun `parses timestamps with and without milliseconds`() {
        assertEquals(
            DeviceIpCollector.parseDate("2026-10-21T12:00:00Z")!! + 123,
            DeviceIpCollector.parseDate("2026-10-21T12:00:00.123Z"),
        )
        assertNull(DeviceIpCollector.parseDate("yesterday"))
    }

    @Test
    fun `validates addresses by family`() {
        assertTrue(DeviceIpCollector.isValid("203.0.113.7", 4))
        assertFalse(DeviceIpCollector.isValid("203.0.113", 4))
        assertFalse(DeviceIpCollector.isValid("2001:db8::1", 4))
        assertTrue(DeviceIpCollector.isValid("2001:db8::1", 6))
        assertFalse(DeviceIpCollector.isValid("203.0.113.7", 6))
        assertFalse(DeviceIpCollector.isValid("::ffff:203.0.113.7", 6))
    }
}
