package com.superwall.superapp.test

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity

/**
 * Bare fragment host for the `RepresentTests` androidTest suite. Debug-source-set only —
 * ships in no release build and pulls in no test libraries. Like
 * [com.superwall.superapp.benchmark.BenchmarkForegroundActivity], it must live in the app
 * (not the test APK) so ActivityScenario can launch it into the app's process.
 *
 * The activity is deliberately empty: the test commits its own fragment into
 * [CONTAINER_ID], keeping all test logic in the androidTest source set.
 */
class PaywallHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = CONTAINER_ID })
    }

    companion object {
        val CONTAINER_ID = View.generateViewId()
    }
}
