package com.superwall.sdk.analytics.internal

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.superwall.sdk.Superwall
import com.superwall.sdk.analytics.internal.trackable.InternalSuperwallEvent
import com.superwall.sdk.identity.IdentityInfo
import com.superwall.sdk.network.Network
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.storage.LastPaywallView
import com.superwall.sdk.storage.Storage
import com.superwall.sdk.storage.TotalPaywallViews
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingLogicTest {
    val store =
        mockk<Storage> {
            every { apiKey } returns "pk_test_1234"
            every { didTrackFirstSession } returns true
            every { didTrackFirstSeen } returns true
            every { get(LastPaywallView) } returns null
            every { get(TotalPaywallViews) } returns 0
        }
    val network = mockk<Network>()

    @Test
    fun should_clean_up_attributes() =
        runTest {
            val ctx = InstrumentationRegistry.getInstrumentation().context
            Superwall.configure(ctx, "pk_test_1234", null, null, null, null)
            val deviceHelper =
                spyk(
                    DeviceHelper(
                        ctx,
                        storage = store,
                        network = network,
                        factory =
                            object : DeviceHelper.Factory {
                                override suspend fun makeIdentityInfo(): IdentityInfo = IdentityInfo("aliasId", "appUserId")

                                override fun makeLocaleIdentifier(): String? = "en-US"
                            },
                    ),
                ) {
                    every { appVersion } returns "123"
                }
            val attributes = deviceHelper.getTemplateDevice()
            val event = InternalSuperwallEvent.DeviceAttributes(HashMap(attributes))
            val res = TrackingLogic.processParameters(event, "appSessionId")
            Log.e("res", res.toString())
            assert(res.eventParams.isEmpty())
        }
}
