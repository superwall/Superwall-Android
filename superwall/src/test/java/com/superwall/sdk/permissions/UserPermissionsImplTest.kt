package com.superwall.sdk.permissions

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UserPermissionsImplTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aPlainActivity_waitsForTheUsersAnswer() =
        runTest {
            Given("a camera permission not yet granted and a plain Activity, like a Unity game's") {
                shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                val permissions = UserPermissionsImpl(app)

                When("the paywall requests the permission") {
                    val status = async { permissions.requestPermission(activity, PermissionType.CAMERA) }
                    runCurrent()

                    Then("it waits for the user instead of returning the current denied state") {
                        assertFalse(status.isCompleted)
                    }

                    When("the user grants it in the headless permission activity") {
                        val intent = shadowOf(activity).nextStartedActivity
                        val requestActivity =
                            Robolectric.buildActivity(PermissionRequestActivity::class.java, intent).setup().get()
                        val request = shadowOf(requestActivity).lastRequestedPermission
                        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
                        requestActivity.onRequestPermissionsResult(
                            request.requestCode,
                            request.requestedPermissions,
                            intArrayOf(PackageManager.PERMISSION_GRANTED),
                        )
                        runCurrent()

                        Then("the request completes as granted") {
                            assertEquals(PermissionStatus.GRANTED, status.await())
                        }
                    }
                }
            }
        }
}
