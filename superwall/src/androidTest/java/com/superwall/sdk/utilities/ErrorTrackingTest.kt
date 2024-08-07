package com.superwall.sdk.utilities

import com.superwall.sdk.storage.ErrorLog
import com.superwall.sdk.storage.Storage
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ErrorTrackingTest {
    @Test
    fun should_save_error_when_occured() =
        runTest {
            val storage =
                mockk<Storage> {
                    every { save(any(), ErrorLog) } just Runs
                    every { get(ErrorLog) } returns null
                }
            val errorTracker: ErrorTracking =
                ErrorTracker(this, storage, {
                    assert(false)
                })

            errorTracker.trackError(Exception("Test Error"))
            coVerify { storage.save(any(), ErrorLog) }
        }

    @Test
    fun should_track_error_when_invoked() =
        runTest {
            val error = ErrorTracking.ErrorOccurence("Test Error", "Test Stacktrace", System.currentTimeMillis())
            val storage =
                mockk<Storage> {
                    every { save(any(), ErrorLog) } just Runs
                    every { get(ErrorLog) } returns error
                    every { remove(ErrorLog) } just Runs
                }

            var tracked = MutableStateFlow(false)
            launch {
                // Will timeout if not tracked
                tracked.filter { it }.first()
            }
            val errorTracker: ErrorTracking =
                ErrorTracker(this, storage, {
                    tracked.update { true }
                })

            coVerify { storage.get(ErrorLog) }
        }
}
