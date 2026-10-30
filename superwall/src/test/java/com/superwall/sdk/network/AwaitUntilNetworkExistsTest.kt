package com.superwall.sdk.network

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class AwaitUntilNetworkExistsTest {
    private lateinit var context: Application
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private fun capabilities(
        transport: Int,
        internet: Boolean = true,
    ): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addTransportType(transport)
            if (internet) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private fun setActiveCapabilities(caps: NetworkCapabilities) {
        shadowOf(connectivityManager).setNetworkCapabilities(connectivityManager.activeNetwork, caps)
    }

    @Test
    fun `ethernet with internet counts as a network`() {
        Given("the active network is ethernet") {
            setActiveCapabilities(capabilities(NetworkCapabilities.TRANSPORT_ETHERNET))

            Then("the network is available") {
                assertTrue(isNetworkAvailable(context))
            }
        }
    }

    @Test
    fun `vpn with internet counts as a network`() {
        Given("the active network is a VPN") {
            setActiveCapabilities(capabilities(NetworkCapabilities.TRANSPORT_VPN))

            Then("the network is available") {
                assertTrue(isNetworkAvailable(context))
            }
        }
    }

    @Test
    fun `unvalidated network with internet counts as a network`() {
        Given("a wifi network behind a captive portal (INTERNET but not VALIDATED)") {
            val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
            setActiveCapabilities(caps)

            Then("the network is available") {
                assertFalse(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                assertTrue(isNetworkAvailable(context))
            }
        }
    }

    @Test
    fun `network without internet capability is not available`() {
        Given("the active network has no INTERNET capability") {
            setActiveCapabilities(capabilities(NetworkCapabilities.TRANSPORT_WIFI, internet = false))

            Then("the network is not available") {
                assertFalse(isNetworkAvailable(context))
            }
        }
    }

    @Test
    fun `no active network is not available`() {
        Given("there is no active network") {
            shadowOf(connectivityManager).setDefaultNetworkActive(false)

            Then("the network is not available") {
                assertFalse(isNetworkAvailable(context))
            }
        }
    }

    @Test
    fun `await gives up after the timeout when there is never a network`() =
        runTest {
            Given("there is no active network") {
                shadowOf(connectivityManager).setDefaultNetworkActive(false)
                var finished = false

                When("we wait for a network") {
                    launch {
                        context.awaitUntilNetworkExists(timeout = 30.seconds)
                        finished = true
                    }
                    advanceTimeBy(29_000)
                    runCurrent()
                    assertFalse(finished)
                    advanceTimeBy(2_000)
                    runCurrent()
                }

                Then("it returns after the timeout and unregisters its callback") {
                    assertTrue(finished)
                    assertEquals(0, shadowOf(connectivityManager).networkCallbacks.size)
                }
            }
        }

    @Test
    fun `await resumes when the default network callback reports internet`() =
        runTest {
            Given("there is no active network") {
                shadowOf(connectivityManager).setDefaultNetworkActive(false)
                var finished = false

                When("an ethernet network with internet comes up") {
                    launch {
                        context.awaitUntilNetworkExists(checkInterval = 10, timeout = 30.seconds)
                        finished = true
                    }
                    runCurrent()
                    val callbacks = shadowOf(connectivityManager).networkCallbacks.toList()
                    assertEquals(1, callbacks.size)
                    val network = ShadowNetworkHelper.anyNetwork()
                    callbacks.forEach {
                        it.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_ETHERNET))
                    }
                    advanceTimeBy(20)
                    runCurrent()
                }

                Then("the wait completes well before the timeout") {
                    assertTrue(finished)
                    assertEquals(0, shadowOf(connectivityManager).networkCallbacks.size)
                }
            }
        }
}

private object ShadowNetworkHelper {
    fun anyNetwork(): android.net.Network =
        org.robolectric.shadows.ShadowNetwork
            .newInstance(42)
}
