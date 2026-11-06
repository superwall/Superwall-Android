package com.superwall.sdk.dependencies

import com.superwall.sdk.config.options.SuperwallOptions
import io.mockk.every
import io.mockk.mockk

/**
 * Attaches a relaxed mock container to [SuperwallGraph], with [options] as its configured options,
 * for tests of code the SDK can't construct (Activities, views, the logger). Detach it with
 * [SuperwallGraph.detach] when the test ends.
 */
internal fun attachMockGraph(options: SuperwallOptions = SuperwallOptions()): DependencyContainer {
    val container = mockk<DependencyContainer>(relaxed = true)
    every { container.configManager.options } returns options
    SuperwallGraph.attach(container)
    return container
}
