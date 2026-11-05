package com.superwall.sdk.analytics

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.analytics.superwall.SuperwallEvent
import com.superwall.sdk.analytics.superwall.SuperwallEventInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SuperwallEventBusTest {
    @Test
    fun `emitted events reach collectors in order`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = SuperwallEventBus(this)
            val first = SuperwallEventInfo(SuperwallEvent.AppOpen(), emptyMap())
            val second = SuperwallEventInfo(SuperwallEvent.AppClose(), mapOf("k" to "v"))
            val received = mutableListOf<SuperwallEventInfo>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    bus.placements.take(2).toList(received)
                }
            bus.emit(first)
            bus.emit(second)
            collector.join()
            Given("a collector of placements") {
                Then("it gets both events in emit order") {
                    assertEquals(listOf(first, second), received)
                }
            }
        }

    @Test
    fun `emitting without collectors does not suspend or fail`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = SuperwallEventBus(this)
            repeat(10) { bus.emit(SuperwallEventInfo(SuperwallEvent.AppOpen(), emptyMap())) }
            Given("no collectors") {
                Then("there is nothing replayed to a late collector") {
                    assertEquals(0, bus.placements.replayCache.size)
                }
            }
        }
}
