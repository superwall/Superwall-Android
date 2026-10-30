package com.superwall.sdk.compose

import android.app.Application
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.superwall.sdk.Superwall
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaywallComposableTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val requestedPlacements = mutableListOf<String>()

    @Before
    fun setUp() {
        // An unconfigured SDK: every paywall request fails, which records the attempt.
        mockkObject(Superwall.Companion)
        every { Superwall.instance } answers {
            throw IllegalStateException("Superwall has not been configured")
        }
    }

    @After
    fun tearDown() {
        unmockkObject(Superwall.Companion)
    }

    @Test
    fun `the default error is a localized string, not a crash`() {
        composeRule.setContent {
            PaywallComposable(placement = "campaign_trigger", delegate = mockk(relaxed = true))
        }

        composeRule.onNodeWithText("No paywall to display").assertExists()
    }

    @Test
    fun `changing the placement loads the new paywall`() {
        val placement = mutableStateOf("first")
        composeRule.setContent {
            PaywallComposable(
                placement = placement.value,
                delegate = mockk(relaxed = true),
                errorComposable = { error ->
                    requestedPlacements += placement.value
                    androidx.compose.material3.Text(error.message ?: "")
                },
            )
        }
        composeRule.waitForIdle()

        placement.value = "second"
        composeRule.waitForIdle()

        assertEquals(listOf("first", "second"), requestedPlacements.distinct())
    }

    @Test
    fun `a context without an activity shows the error instead of throwing`() {
        val appOnly = ContextWrapper(ApplicationProvider.getApplicationContext<Application>())
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides appOnly) {
                PaywallComposable(placement = "campaign_trigger", delegate = mockk(relaxed = true))
            }
        }

        composeRule.onNodeWithText("No paywall to display").assertExists()
    }

    @Test
    fun `findNearestActivityOrNull unwraps context wrappers`() {
        val activity = composeRule.activity
        assertSame(activity, ContextWrapper(ContextWrapper(activity)).findNearestActivityOrNull())
        assertNull(ContextWrapper(ApplicationProvider.getApplicationContext<Application>()).findNearestActivityOrNull())
    }
}
