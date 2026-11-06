package com.superwall.sdk.deeplinks

import android.net.Uri
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.ConfigState
import com.superwall.sdk.dependencies.DependencyContainer
import com.superwall.sdk.dependencies.SuperwallGraph
import com.superwall.sdk.models.config.Config
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkRouterGraphTest {
    private var attached: DependencyContainer? = null

    @After
    fun tearDown() {
        SuperwallGraph.detach(attached)
    }

    private fun attachContainer(
        state: ConfigState,
        router: DeepLinkRouter,
    ): DependencyContainer =
        mockk<DependencyContainer>(relaxed = true) {
            every { configManager.configState } returns MutableStateFlow(state)
            every { deepLinkRouter } returns router
        }.also {
            attached = it
            SuperwallGraph.attach(it)
        }

    @Test
    fun `a configured graph routes the deep link to its router`() {
        Given("a container whose config was retrieved") {
            val router = mockk<DeepLinkRouter>(relaxed = true)
            attachContainer(ConfigState.Retrieved(Config.stub()), router)
            val uri = Uri.parse("https://example.com/not-superwall")

            When("the app hands over a deep link") {
                DeepLinkRouter.handleDeepLink(uri)
            }

            Then("the container's router handles it") {
                verify(exactly = 1) { router.handleDeepLink(uri) }
            }
        }
    }

    @Test
    fun `a graph still fetching config does not route the deep link yet`() {
        Given("a container whose config is still being retrieved") {
            val router = mockk<DeepLinkRouter>(relaxed = true)
            attachContainer(ConfigState.Retrieving, router)
            val uri = Uri.parse("https://example.com/not-superwall")

            val result =
                When("the app hands over a deep link") {
                    DeepLinkRouter.handleDeepLink(uri)
                }

            Then("the router is not called and a non-Superwall link is reported as unhandled") {
                verify(exactly = 0) { router.handleDeepLink(any()) }
                assertTrue(result.isFailure)
            }
        }
    }
}
