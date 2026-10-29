package com.superwall.sdk.network

import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.PrivateSuperwallEvents
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.logger.LogQueue
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.events.EventsRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Every log reaches [SuperwallDelegate.handleLog], whatever its level, and customers forward
 * those to their own logging. A failed `/events` request must not hand it the events'
 * parameters, which can hold a push token that may only go to Superwall.
 */
class EventsRequestLogTest {
    private data class Log(
        val message: String?,
        val info: Map<String, Any>?,
        val error: Throwable?,
    )

    private val logs = mutableListOf<Log>()
    private val collectorService = mockk<CollectorService>()

    private val network =
        Network(
            baseHostService = mockk(relaxed = true),
            collectorService = collectorService,
            enrichmentService = mockk(relaxed = true),
            mmpService = mockk(relaxed = true),
            factory = mockk(relaxed = true),
            subscriptionService = mockk(relaxed = true),
        )

    @Before
    fun setUp() {
        LogQueue.synchronous = true
        val adapter = SuperwallDelegateAdapter()
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun handleLog(
                    level: String,
                    scope: String,
                    message: String?,
                    info: Map<String, Any>?,
                    error: Throwable?,
                ) {
                    logs += Log(message, info, error)
                }
            }
        val container = mockk<DependencyContainer>()
        every { container.delegateAdapter } returns adapter
        val superwall = mockk<Superwall>()
        every { superwall.dependencyContainer } returns container
        every { superwall.options } returns SuperwallOptions()
        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns superwall
        Superwall.initialized = true
    }

    @After
    fun tearDown() {
        Superwall.initialized = false
        unmockkObject(Superwall.Companion)
        LogQueue.synchronous = false
        SuperwallDelegateAdapter()
    }

    @Test
    fun `a failed events request logs the event names, never their parameters`() =
        runTest {
            coEvery { collectorService.events(any()) } returns
                Either.Failure(NetworkError.Unknown(Exception("Failed, response code 500")))
            val request =
                EventsRequest(
                    listOf(
                        EventData(
                            name = PrivateSuperwallEvents.NotificationToken.NAME,
                            parameters = mapOf("channel" to "fcm", "address" to TOKEN, "enabled" to true),
                            createdAt = Date(),
                        ),
                        EventData(
                            name = "custom_event",
                            parameters = mapOf("email" to EMAIL),
                            createdAt = Date(),
                        ),
                    ),
                )

            network.sendEvents(request)

            val failure = logs.single { it.message == "Request Failed: /events" }
            assertEquals(
                mapOf(
                    "event_count" to 2,
                    "event_names" to listOf(PrivateSuperwallEvents.NotificationToken.NAME, "custom_event"),
                ),
                failure.info,
            )
            logs.forEach { log ->
                val logged = "${log.message} ${log.info} ${log.error}"
                assertFalse(logged, logged.contains(TOKEN))
                assertFalse(logged, logged.contains(EMAIL))
                assertFalse(logged, logged.contains("address"))
            }
        }

    private companion object {
        const val TOKEN = "fGx7Q2kTRaSc:APA91bH-Ur1Xy_ZkLmN0pQ"
        const val EMAIL = "someone@example.com"
    }
}
