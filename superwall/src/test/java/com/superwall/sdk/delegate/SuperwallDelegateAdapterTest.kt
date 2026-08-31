package com.superwall.sdk.delegate

import com.superwall.sdk.paywall.presentation.PaywallInfo
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SuperwallDelegateAdapterTest {
    private val paywallInfo = mockk<PaywallInfo>()

    @Test
    fun `kotlin delegate linkage failure does not escape or stop later callbacks`() {
        val adapter = SuperwallDelegateAdapter()
        var didPresentCount = 0
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun willPresentPaywall(withInfo: PaywallInfo) {
                    throw NoClassDefFoundError("missing.KotlinDelegateDependency")
                }

                override fun didPresentPaywall(withInfo: PaywallInfo) {
                    didPresentCount += 1
                }
            }

        adapter.willPresentPaywall(paywallInfo)
        adapter.didPresentPaywall(paywallInfo)

        assertEquals(1, didPresentCount)
    }

    @Test
    fun `kotlin delegate exception does not escape or stop later callbacks`() {
        val adapter = SuperwallDelegateAdapter()
        var didPresentCount = 0
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun willPresentPaywall(withInfo: PaywallInfo) {
                    throw IllegalArgumentException("bad lazy value")
                }

                override fun didPresentPaywall(withInfo: PaywallInfo) {
                    didPresentCount += 1
                }
            }

        adapter.willPresentPaywall(paywallInfo)
        adapter.didPresentPaywall(paywallInfo)

        assertEquals(1, didPresentCount)
    }

    @Test
    fun `java delegate linkage failure does not escape or stop later callbacks`() {
        val adapter = SuperwallDelegateAdapter()
        var didPresentCount = 0
        adapter.javaDelegate =
            object : SuperwallDelegateJava {
                override fun willPresentPaywall(paywallInfo: PaywallInfo) {
                    throw NoClassDefFoundError("missing.JavaDelegateDependency")
                }

                override fun didPresentPaywall(paywallInfo: PaywallInfo) {
                    didPresentCount += 1
                }
            }

        adapter.willPresentPaywall(paywallInfo)
        adapter.didPresentPaywall(paywallInfo)

        assertEquals(1, didPresentCount)
    }

    @Test
    fun `java delegate exception does not escape or stop later callbacks`() {
        val adapter = SuperwallDelegateAdapter()
        var didPresentCount = 0
        adapter.javaDelegate =
            object : SuperwallDelegateJava {
                override fun willPresentPaywall(paywallInfo: PaywallInfo) {
                    throw IllegalArgumentException("bad lazy value")
                }

                override fun didPresentPaywall(paywallInfo: PaywallInfo) {
                    didPresentCount += 1
                }
            }

        adapter.willPresentPaywall(paywallInfo)
        adapter.didPresentPaywall(paywallInfo)

        assertEquals(1, didPresentCount)
    }

    @Test
    fun `kotlin delegate remains the only target when both delegates are set`() {
        val adapter = SuperwallDelegateAdapter()
        var kotlinCount = 0
        var javaCount = 0
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun willRedeemLink() {
                    kotlinCount += 1
                    throw IllegalArgumentException("kotlin failure")
                }
            }
        adapter.javaDelegate =
            object : SuperwallDelegateJava {
                override fun willRedeemLink() {
                    javaCount += 1
                }
            }

        adapter.willRedeemLink()

        assertEquals(1, kotlinCount)
        assertEquals(0, javaCount)
    }

    @Test
    fun `virtual machine errors still escape the delegate boundary`() {
        val adapter = SuperwallDelegateAdapter()
        val fatalError = object : VirtualMachineError("fatal") {}
        adapter.kotlinDelegate =
            object : SuperwallDelegate {
                override fun willRedeemLink() {
                    throw fatalError
                }
            }

        val thrown = assertFailsWith<VirtualMachineError> { adapter.willRedeemLink() }

        assertEquals(fatalError, thrown)
    }
}
