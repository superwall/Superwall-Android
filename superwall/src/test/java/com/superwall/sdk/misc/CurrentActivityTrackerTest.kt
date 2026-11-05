package com.superwall.sdk.misc

import android.app.Activity
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class CurrentActivityTrackerTest {
    @Test
    fun `stopping the current activity clears it`() {
        val tracker = CurrentActivityTracker()
        val activity = mockk<Activity>(relaxed = true)

        tracker.onActivityStarted(activity)
        tracker.onActivityStopped(activity)

        assertNull(tracker.getCurrentActivity())
    }

    @Test
    fun `stopping an older activity does not clear the newer activity`() {
        val tracker = CurrentActivityTracker()
        val olderActivity = mockk<Activity>(relaxed = true)
        val newerActivity = mockk<Activity>(relaxed = true)

        tracker.onActivityStarted(olderActivity)
        tracker.onActivityStarted(newerActivity)
        tracker.onActivityStopped(olderActivity)

        assertSame(newerActivity, tracker.getCurrentActivity())
    }
}
