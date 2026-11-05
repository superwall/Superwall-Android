package com.superwall.sdk.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PermissionRequestActivityTest {
    private lateinit var host: Activity

    @Before
    fun setUp() {
        // A plain android.app.Activity, like UnityPlayerActivity.
        host = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    /** Starts the [PermissionRequestActivity] that [host] was asked to launch. */
    private fun startRequestActivity(): ActivityController<PermissionRequestActivity> {
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(PermissionRequestActivity::class.java.name, intent.component?.className)
        return Robolectric.buildActivity(PermissionRequestActivity::class.java, intent).setup()
    }

    private fun PermissionRequestActivity.answer(granted: Boolean) {
        val request = shadowOf(this).lastRequestedPermission
        onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
    }

    @Test
    fun theUsersAnswer_isDeliveredAndTheActivityFinishes() {
        Given("a notification permission requested for a plain Activity") {
            var result: Boolean? = null
            PermissionRequestActivity.request(host, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { result = it }
            val controller = startRequestActivity()
            val activity = controller.get()

            When("the user grants it") {
                assertArrayEquals(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    shadowOf(activity).lastRequestedPermission.requestedPermissions,
                )
                assertNull(result)
                activity.answer(granted = true)

                Then("the grant reaches the caller and the headless activity finishes") {
                    assertEquals(true, result)
                    assertTrue(activity.isFinishing)
                }
            }
        }
    }

    @Test
    fun destroyedWithoutAnAnswer_completesAsDenied() {
        Given("a permission request whose activity is showing the prompt") {
            val results = mutableListOf<Boolean>()
            PermissionRequestActivity.request(host, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { results += it }
            val controller = startRequestActivity()

            When("the activity is finished and destroyed before the user answers") {
                controller.get().finish()
                controller.pause().stop().destroy()

                Then("the request completes once, as denied") {
                    assertEquals(listOf(false), results)
                }
            }
        }
    }

    @Test
    fun concurrentRequests_eachGetTheirOwnAnswer() {
        Given("two permission requests in flight at once") {
            var first: Boolean? = null
            var second: Boolean? = null
            PermissionRequestActivity.request(host, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { first = it }
            val firstActivity = startRequestActivity().get()
            PermissionRequestActivity.request(host, arrayOf(Manifest.permission.CAMERA)) { second = it }
            val secondActivity = startRequestActivity().get()

            When("the second is denied before the first is granted") {
                secondActivity.answer(granted = false)
                firstActivity.answer(granted = true)

                Then("each caller gets the answer to its own request") {
                    assertEquals(true, first)
                    assertEquals(false, second)
                }
            }
        }
    }

    @Test
    fun aCancelledRequest_neverCallsBack() {
        Given("a permission request that is showing the prompt") {
            var result: Boolean? = null
            val cancel = PermissionRequestActivity.request(host, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { result = it }
            val activity = startRequestActivity().get()

            When("it is cancelled and the user then answers") {
                cancel()
                activity.answer(granted = true)

                Then("the caller is not called back") {
                    assertNull(result)
                    assertTrue(activity.isFinishing)
                }
            }
        }
    }
}
