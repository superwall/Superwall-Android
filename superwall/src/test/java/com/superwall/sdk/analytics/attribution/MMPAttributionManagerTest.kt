package com.superwall.sdk.analytics.attribution

import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.analytics.internal.trackable.TrackableSuperwallEvent
import com.superwall.sdk.analytics.superwall.AttributionMatchInfo
import com.superwall.sdk.identity.IdentityManager
import com.superwall.sdk.misc.Either
import com.superwall.sdk.network.MmpMatchResponse
import com.superwall.sdk.network.NetworkError
import com.superwall.sdk.storage.LocalStorage
import com.superwall.sdk.storage.MMPAcquisitionData
import com.superwall.sdk.storage.Storable
import io.mockk.every
import io.mockk.mockk
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.models.config.AttributionOptions
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.models.config.MmpAttributionOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors iOS's `MMPAttributionManager` behaviour: cache the resolved `acquisition_*`
 * payload, merge it into user attributes, track the outcome, expose the cache for resets
 * without re-hitting the backend, and only match once config turns the MMP on.
 */
class MMPAttributionManagerTest {
    private val stored = mutableMapOf<String, Any?>()

    private fun storage(): LocalStorage {
        val storage = mockk<LocalStorage>(relaxed = true)
        every { storage.write(any<Storable<Any>>(), any()) } answers {
            stored[firstArg<Storable<Any>>().key] = secondArg()
        }
        every { storage.read(any<Storable<Any>>()) } answers {
            stored[firstArg<Storable<Any>>().key]
        }
        return storage
    }

    private fun identityManager(attributes: Map<String, Any> = emptyMap()): IdentityManager {
        val identityManager = mockk<IdentityManager>(relaxed = true)
        every { identityManager.userAttributes } returns attributes
        return identityManager
    }

    private fun matched(
        acquisitionAttributes: Map<String, JsonPrimitive>? =
            mapOf(
                "acquisition_source" to JsonPrimitive("tiktok"),
                "acquisition_campaign" to JsonPrimitive("spring_sale"),
            ),
    ) = MmpMatchResponse(
        matched = true,
        confidence = AttributionMatchInfo.Confidence.HIGH,
        matchScore = 0.92,
        network = "tiktok_ads",
        acquisitionAttributes = acquisitionAttributes,
        breakdown = mapOf("reason" to JsonPrimitive("ip_and_fingerprint")),
    )

    @Test
    fun `a successful match caches the payload, merges attributes and tracks the outcome`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()
            val applied = mutableListOf<Map<String, Any?>>()

            val manager =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage(),
                    identityManager = identityManager(),
                    track = { tracked += it },
                    setUserAttributes = { applied += it },
                    sendMatchRequest = { Either.Success(matched()) },
                )

            val completed = manager.matchInstall(installReferrerClickId = 42L)

            assertTrue(completed)
            assertEquals("tiktok", applied.single()["acquisition_source"])
            assertEquals("spring_sale", applied.single()["acquisition_campaign"])
            assertEquals(2, (stored[MMPAcquisitionData.key] as Map<*, *>).size)

            val event = tracked.single() as InternalSuperwallEvent.AttributionMatch
            assertTrue(event.info.matched)
            assertEquals(AttributionMatchInfo.Provider.MMP, event.info.provider)
            assertEquals("tiktok", event.info.source)
            assertEquals(AttributionMatchInfo.Confidence.HIGH, event.info.confidence)
            assertEquals(0.92, event.info.matchScore!!, 0.0001)
            assertEquals("ip_and_fingerprint", event.info.reason)
        }

    @Test
    fun `source falls back to the network name when no acquisition_source is present`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()

            val manager =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage(),
                    identityManager = identityManager(),
                    track = { tracked += it },
                    setUserAttributes = {},
                    sendMatchRequest = {
                        Either.Success(
                            matched(acquisitionAttributes = mapOf("acquisition_campaign" to JsonPrimitive("x"))),
                        )
                    },
                )

            manager.matchInstall(null)

            val event = tracked.single() as InternalSuperwallEvent.AttributionMatch
            assertEquals("tiktok_ads", event.info.source)
        }

    @Test
    fun `an unmatched response still counts as a completed request`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()

            val manager =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage(),
                    identityManager = identityManager(),
                    track = { tracked += it },
                    setUserAttributes = {},
                    sendMatchRequest = { Either.Success(MmpMatchResponse(matched = false)) },
                )

            // A processed request that found nothing must not be retried on next launch.
            assertTrue(manager.matchInstall(null))
            assertFalse((tracked.single() as InternalSuperwallEvent.AttributionMatch).info.matched)
            assertNull(stored[MMPAcquisitionData.key])
        }

    @Test
    fun `a failed request tracks request_failed and does not count as completed`() =
        runTest {
            val tracked = mutableListOf<TrackableSuperwallEvent>()

            val manager =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage(),
                    identityManager = identityManager(),
                    track = { tracked += it },
                    setUserAttributes = {},
                    sendMatchRequest = { Either.Failure(NetworkError.Timeout) },
                )

            assertFalse(manager.matchInstall(null))

            val event = tracked.single() as InternalSuperwallEvent.AttributionMatch
            assertFalse(event.info.matched)
            assertEquals("request_failed", event.info.reason)
        }

    @Test
    fun `attributes already on the user are not re-applied`() =
        runTest {
            val applied = mutableListOf<Map<String, Any?>>()

            val manager =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage(),
                    identityManager =
                        identityManager(
                            mapOf(
                                "acquisition_source" to "tiktok",
                                "acquisition_campaign" to "spring_sale",
                            ),
                        ),
                    track = {},
                    setUserAttributes = { applied += it },
                    sendMatchRequest = { Either.Success(matched()) },
                )

            manager.matchInstall(null)

            assertTrue(applied.isEmpty())
        }

    @Test
    fun `reapply restores the cached payload to a new user without re-matching`() =
        runTest {
            val applied = mutableListOf<Map<String, Any?>>()
            var requests = 0
            val storage = storage()

            // First run: match resolves and caches.
            MMPAttributionManager(
                configState = MutableStateFlow(ConfigState.None),
                scope = this,
                storage = storage,
                identityManager = identityManager(),
                track = {},
                setUserAttributes = {},
                sendMatchRequest = {
                    requests += 1
                    Either.Success(matched())
                },
            ).matchInstall(null)

            // After `reset()` the user's attributes are gone, but the install-scoped cache isn't.
            val cached =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage,
                    identityManager = identityManager(),
                    track = {},
                    setUserAttributes = {},
                    sendMatchRequest = {
                        requests += 1
                        Either.Success(matched())
                    },
                ).cachedAcquisitionAttributes()

            assertEquals(1, requests)
            assertEquals("tiktok", cached["acquisition_source"])
        }

    @Test
    fun `cached attributes are empty when no match ever resolved`() =
        runTest {
            val cached =
                MMPAttributionManager(
                    configState = MutableStateFlow(ConfigState.None),
                    scope = this,
                    storage = storage(),
                    identityManager = identityManager(),
                    track = {},
                    setUserAttributes = {},
                    sendMatchRequest = { Either.Failure(NetworkError.Timeout) },
                ).cachedAcquisitionAttributes()

            assertTrue(cached.isEmpty())
        }

    private fun configWithMmp(enabled: Boolean) =
        ConfigState.Retrieved(
            Config.stub().copy(attributionOptions = AttributionOptions(mmp = MmpAttributionOptions(enabled))),
        )

    private fun gatedManager(
        scope: CoroutineScope,
        configState: MutableStateFlow<ConfigState>,
    ) = MMPAttributionManager(
        configState = configState,
        scope = scope,
        storage = storage(),
        identityManager = identityManager(),
        track = {},
        setUserAttributes = {},
        sendMatchRequest = { Either.Failure(NetworkError.Timeout) },
    )

    @Test
    fun `the match never starts while config has the MMP off`() =
        runTest {
            val configState = MutableStateFlow<ConfigState>(configWithMmp(enabled = false))
            var starts = 0

            gatedManager(backgroundScope, configState).matchInstallOnceEnabled {
                starts += 1
                true
            }
            runCurrent()

            assertEquals(0, starts)
        }

    @Test
    fun `the match starts once when config turns the MMP on`() =
        runTest {
            val configState = MutableStateFlow<ConfigState>(ConfigState.Retrieving)
            var starts = 0
            val manager = gatedManager(backgroundScope, configState)

            manager.matchInstallOnceEnabled {
                starts += 1
                true
            }
            runCurrent()
            assertEquals(0, starts)

            configState.value = configWithMmp(enabled = true)
            runCurrent()
            manager.startMatchIfEnabled()

            assertEquals(1, starts)
        }

    @Test
    fun `a match skipped while opted out starts when tracking is turned back on`() =
        runTest {
            val configState = MutableStateFlow<ConfigState>(configWithMmp(enabled = true))
            var optedOut = true
            var starts = 0
            val manager = gatedManager(backgroundScope, configState)

            manager.matchInstallOnceEnabled {
                if (optedOut) return@matchInstallOnceEnabled false
                starts += 1
                true
            }
            runCurrent()
            assertEquals(0, starts)

            optedOut = false
            manager.startMatchIfEnabled()
            manager.startMatchIfEnabled()

            assertEquals(1, starts)
        }
}
