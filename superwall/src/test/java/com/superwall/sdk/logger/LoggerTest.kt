package com.superwall.sdk.logger

import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Superwall
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.assertFalse
import com.superwall.sdk.assertTrue
import com.superwall.sdk.config.options.SuperwallOptions
import com.superwall.sdk.delegate.SuperwallDelegate
import com.superwall.sdk.delegate.SuperwallDelegateAdapter
import com.superwall.sdk.dependencies.DependencyContainer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Records what was printed and which thread printed it.
 */
private class RecordingPrintStream : PrintStream(ByteArrayOutputStream()) {
    val lines = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArrayList<String>()

    private fun record(value: Any?) {
        lines.add(value?.toString() ?: "")
        threads.add(Thread.currentThread().name)
    }

    override fun println(x: Any?) = record(x)

    override fun println(x: String?) = record(x)
}

class LoggerTest {
    private val originalOut = System.out
    private lateinit var out: RecordingPrintStream

    /**
     * Drains the log queue by posting a sentinel behind the logs under test. The queue is
     * FIFO on a single thread, so once the sentinel runs everything before it has been
     * delivered.
     */
    private fun awaitLogQueue() {
        val latch = CountDownLatch(1)
        LogQueue.post { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Before
    fun setUp() {
        out = RecordingPrintStream()
        System.setOut(out)
        // A fresh adapter clears the global "a delegate is listening" flag.
        SuperwallDelegateAdapter()
    }

    @After
    fun tearDown() {
        System.setOut(originalOut)
        LogQueue.synchronous = false
        SuperwallDelegateAdapter()
    }

    @Test
    fun `does not build a log that nothing will consume`() {
        Given("no delegate is registered and Superwall is not configured") {
            var built = false

            When("logging below the default console level of warn") {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallView,
                    message = {
                        built = true
                        "expensive"
                    },
                )
            }

            Then("the message is never built") {
                assertFalse(built)
            }

            And("nothing is printed") {
                awaitLogQueue()
                assertTrue(out.lines.isEmpty())
            }
        }
    }

    @Test
    fun `builds a log when a delegate is listening`() {
        Given("a registered delegate") {
            val adapter = SuperwallDelegateAdapter()
            adapter.kotlinDelegate = object : SuperwallDelegate {}
            var built = false

            When("logging below the console level") {
                Logger.debug(
                    logLevel = LogLevel.debug,
                    scope = LogScope.paywallView,
                    message = {
                        built = true
                        "expensive"
                    },
                )
            }

            Then("the message is built for the delegate") {
                assertTrue(built)
            }
        }
    }

    @Test
    fun `clears the delegate flag when the delegate is removed`() {
        Given("a registered delegate") {
            val adapter = SuperwallDelegateAdapter()
            adapter.kotlinDelegate = object : SuperwallDelegate {}
            assertTrue(SuperwallDelegateAdapter.hasAnyDelegate)

            When("the delegate is set back to null") {
                adapter.kotlinDelegate = null
            }

            Then("no delegate is listening") {
                assertFalse(SuperwallDelegateAdapter.hasAnyDelegate)
            }
        }
    }

    @Test
    fun `delivers logs off the calling thread`() {
        Given("a log printed at the default console level") {
            val callingThread = Thread.currentThread().name

            When("logging an error") {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallView,
                    message = "off thread",
                )
                awaitLogQueue()
            }

            Then("it was printed from another thread") {
                assertTrue(out.threads.isNotEmpty())
                assertTrue(out.threads.none { it == callingThread })
            }
        }
    }

    @Test
    fun `preserves log order`() {
        Given("many logs from one thread") {
            When("logging in sequence") {
                repeat(50) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        scope = LogScope.paywallView,
                        message = "message $it",
                    )
                }
                awaitLogQueue()
            }

            Then("they are printed in submission order") {
                val printed = out.lines.filter { it.contains("message ") }
                assertEquals(50, printed.size)
                printed.forEachIndexed { index, line ->
                    assertTrue(line.contains("message $index"))
                }
            }
        }
    }

    @Test
    fun `prints the message alongside its info`() {
        Given("a log carrying info") {
            When("logging with a non-empty info map") {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallView,
                    message = "the message",
                    info = mapOf("key" to "value"),
                )
                awaitLogQueue()
            }

            Then("the message is printed") {
                assertTrue(out.lines.any { it.contains("the message") })
            }

            And("the info is printed") {
                assertTrue(out.lines.any { it.contains("key") && it.contains("value") })
            }
        }
    }

    @Test
    fun `omits an empty info map`() {
        Given("a log with an empty info map") {
            When("logging") {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallView,
                    message = "no info",
                    info = emptyMap(),
                )
                awaitLogQueue()
            }

            Then("the message is printed") {
                assertTrue(out.lines.any { it.contains("no info") })
            }

            And("no info line is printed") {
                assertFalse(out.lines.any { it.startsWith("info:") })
            }
        }
    }

    @Test
    fun `keeps delivering after a listener throws`() {
        Given("a log delivery that throws") {
            When("posting a throwing block followed by a healthy one") {
                LogQueue.post { throw IllegalStateException("boom") }
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.paywallView,
                    message = "survives",
                )
                awaitLogQueue()
            }

            Then("the later log is still delivered") {
                assertTrue(out.lines.any { it.contains("survives") })
            }
        }
    }

    @Test
    fun `keeps delivering when the configured delegate handleLog throws`() {
        val adapter = SuperwallDelegateAdapter()
        var deliveryCount = 0
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun handleLog(
                    level: String,
                    scope: String,
                    message: String?,
                    info: Map<String, Any>?,
                    error: Throwable?,
                ) {
                    deliveryCount += 1
                    if (deliveryCount == 1) {
                        throw IllegalArgumentException("bad lazy value")
                    }
                }
            }

        val dependencyContainer = mockk<DependencyContainer>()
        every { dependencyContainer.delegateAdapter } returns adapter
        val superwall = mockk<Superwall>()
        every { superwall.dependencyContainer } returns dependencyContainer
        every { superwall.options } returns SuperwallOptions()

        mockkObject(Superwall.Companion)
        every { Superwall.instance } returns superwall
        Superwall.initialized = true

        try {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.paywallView,
                message = "delegate fails",
            )
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.paywallView,
                message = "delegate survives",
            )
            awaitLogQueue()

            assertEquals(2, deliveryCount)
            assertTrue(out.lines.any { it.contains("delegate survives") })
        } finally {
            Superwall.initialized = false
            unmockkObject(Superwall.Companion)
        }
    }
}
