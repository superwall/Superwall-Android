package com.superwall.sdk.network

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class RequestExecutorTest {
    @Test
    fun `execute runs off the caller's dispatcher`() =
        runBlocking {
            Given("a request executor with a dedicated io context") {
                val ioThreadName = "test-io"
                val ioDispatcher =
                    Executors
                        .newSingleThreadExecutor { Thread(it, ioThreadName) }
                        .asCoroutineDispatcher()
                var headersThread: String? = null
                val executor =
                    RequestExecutor(ioContext = ioDispatcher) { _, _ ->
                        headersThread = Thread.currentThread().name
                        emptyMap()
                    }
                val requestData =
                    NetworkRequestData<Any>(
                        factory = { _, _ -> emptyMap() },
                        timeout = 1.seconds,
                    )

                When("execute is called from a caller dispatcher") {
                    val callerContext = newSingleThreadContext("caller")
                    val callerThread =
                        withContext(callerContext) {
                            executor.execute(requestData)
                            Thread.currentThread().name
                        }

                    Then("the request work runs on the io context, not the caller's thread") {
                        assertEquals(ioThreadName, headersThread)
                        assertNotEquals(callerThread, headersThread)
                    }
                    callerContext.close()
                    ioDispatcher.close()
                }
            }
        }
}
