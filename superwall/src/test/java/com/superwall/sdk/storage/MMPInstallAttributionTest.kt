package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.misc.IOScope
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Mirrors `MMPInstallAttributionTests` on iOS.
 */
@RunWith(RobolectricTestRunner::class)
class MMPInstallAttributionTest {
    private lateinit var context: Context
    private lateinit var storage: LocalStorage

    private val dayMs = 24L * 60 * 60 * 1000

    private fun installedDaysAgo(days: Double): Long =
        System.currentTimeMillis() - (days * dayMs).toLong()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val json = Json { ignoreUnknownKeys = true }
        storage =
            LocalStorage(
                context = context,
                json = json,
                _apiKey = "test_key",
                factory = mockk(relaxed = true),
                ioScope = IOScope(UnconfinedTestDispatcher()),
                cache = Cache(context, ioQueue = UnconfinedTestDispatcher(), json = json),
                coreDataManager = mockk(relaxed = true),
            )
    }

    @Test
    fun `fresh install within the window is eligible and is marked eligible`() {
        val result =
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = false,
                appInstalledAtMillis = installedDaysAgo(1.0),
            )

        assertTrue(result)
        assertEquals(true, storage.read(IsEligibleForMMPInstallAttributionMatch))
    }

    @Test
    fun `an already completed request is not retried`() {
        storage.write(DidCompleteMMPInstallAttributionRequest, true)

        val result =
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = false,
                appInstalledAtMillis = installedDaysAgo(1.0),
            )

        assertFalse(result)
    }

    @Test
    fun `an upgrader that never became eligible is skipped`() {
        // Already tracked app install on a previous SDK version, and was never marked eligible.
        val result =
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = true,
                appInstalledAtMillis = installedDaysAgo(1.0),
            )

        assertFalse(result)
        assertNull(storage.read(IsEligibleForMMPInstallAttributionMatch))
    }

    @Test
    fun `an eligible returning session retries the match`() {
        storage.write(IsEligibleForMMPInstallAttributionMatch, true)

        val result =
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = true,
                appInstalledAtMillis = installedDaysAgo(2.0),
            )

        assertTrue(result)
    }

    @Test
    fun `an install outside the seven day window is skipped`() {
        val result =
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = false,
                appInstalledAtMillis = installedDaysAgo(8.0),
            )

        assertFalse(result)
    }

    @Test
    fun `an install right at the window boundary is still eligible`() {
        val result =
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = false,
                appInstalledAtMillis = installedDaysAgo(6.9),
            )

        assertTrue(result)
    }

    @Test
    fun `an unknown install date is treated as within the window`() {
        // Fails open, matching iOS: an unusable install date must not silently drop attribution.
        assertTrue(
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = false,
                appInstalledAtMillis = 0L,
            ),
        )
    }

    @Test
    fun `a future install date from a skewed clock is treated as within the window`() {
        assertTrue(
            storage.shouldAttemptInitialMMPInstallAttributionMatch(
                hadTrackedAppInstallBeforeConfigure = false,
                appInstalledAtMillis = System.currentTimeMillis() + dayMs,
            ),
        )
    }

    @Test
    fun `a completed request is not re-run once recorded`() {
        var calls = 0
        storage.write(DidCompleteMMPInstallAttributionRequest, true)

        storage.recordMMPInstallAttributionRequest {
            calls += 1
            true
        }

        assertEquals(0, calls)
    }

    @Test
    fun `a failed request does not mark the match complete`() {
        storage.recordMMPInstallAttributionRequest { false }

        assertNull(storage.read(DidCompleteMMPInstallAttributionRequest))
    }

    @Test
    fun `a successful request marks the match complete`() {
        storage.recordMMPInstallAttributionRequest { true }

        assertEquals(true, storage.read(DidCompleteMMPInstallAttributionRequest))
    }
}
