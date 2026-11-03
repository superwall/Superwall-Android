package com.superwall.sdk.config

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import com.superwall.sdk.And
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.IOScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigChangeObserverTest {
    private val appContext = mockk<Context>(relaxed = true)
    private val context = mockk<Context> { every { applicationContext } returns appContext }
    private var changes = 0
    private val observer =
        AppConfigChangeObserver(
            context = context,
            scope = IOScope(Dispatchers.Unconfined),
            onChange = { changes++ },
        )

    @Test
    fun `a configuration change calls onChange`() =
        Given("a registered observer") {
            val callbacks = slot<ComponentCallbacks>()
            every { appContext.registerComponentCallbacks(capture(callbacks)) } returns Unit
            observer.register()
            When("the configuration changes") {
                callbacks.captured.onConfigurationChanged(Configuration())
                Then("onChange runs once") {
                    assertEquals(1, changes)
                }
            }
        }

    @Test
    fun `registering twice registers once, and unregister removes it`() =
        Given("an observer registered twice") {
            observer.register()
            observer.register()
            Then("one callback is registered") {
                verify(exactly = 1) { appContext.registerComponentCallbacks(any()) }
            }
            When("it is unregistered twice") {
                observer.unregister()
                observer.unregister()
                And("the callback is removed once") {
                    verify(exactly = 1) { appContext.unregisterComponentCallbacks(any()) }
                }
            }
        }
}
