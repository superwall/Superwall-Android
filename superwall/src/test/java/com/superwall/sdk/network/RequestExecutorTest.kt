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
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class RequestExecutorTest {
    @Test
    fun `execute runs off the caller's dispatcher`() =
        runBlocking {
            Given("a request executor with a dedicated io context") {
                var ioThread: Thread? = null
                val ioDispatcher =
                    Executors
                        .newSingleThreadExecutor { Thread(it, "test-io").also { t -> ioThread = t } }
                        .asCoroutineDispatcher()
                var headersThread: Thread? = null
                val executor =
                    RequestExecutor(ioContext = ioDispatcher) { _, _ ->
                        headersThread = Thread.currentThread()
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
                            Thread.currentThread()
                        }

                    Then("the request work runs on the io context, not the caller's thread") {
                        assertNotNull(headersThread)
                        assertEquals(ioThread, headersThread)
                        assertNotEquals(callerThread, headersThread)
                    }
                    callerContext.close()
                    ioDispatcher.close()
                }
            }
        }
}
