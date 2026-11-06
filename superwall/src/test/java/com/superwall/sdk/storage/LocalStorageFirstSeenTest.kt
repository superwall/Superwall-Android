package com.superwall.sdk.storage

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.Trackable
import com.superwall.sdk.misc.IOScope
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalStorageFirstSeenTest {
    @Test
    fun `first seen is tracked through the injected track function`() {
        Given("storage built with an injected track function") {
            val context = RuntimeEnvironment.getApplication()
            val json = Json { ignoreUnknownKeys = true }
            val tracked = CompletableDeferred<Trackable>()
            val storage =
                LocalStorage(
                    context = context,
                    json = json,
                    _apiKey = "test_key",
                    factory = mockk(relaxed = true),
                    ioScope = IOScope(UnconfinedTestDispatcher()),
                    cache = Cache(context, ioQueue = UnconfinedTestDispatcher(), json = json),
                    coreDataManager = mockk(relaxed = true),
                    track = { tracked.complete(it) },
                )

            When("first seen is recorded") {
                storage.recordFirstSeenTracked()

                Then("the FirstSeen event reaches the injected function, not Superwall.instance") {
                    val event = runBlocking { withTimeout(5_000) { tracked.await() } }
                    assertTrue(event is InternalSuperwallEvent.FirstSeen)
                }
            }
        }
    }
}
