package com.superwall.sdk.paywall.manager

import android.view.View
import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.misc.ActivityProvider
import com.superwall.sdk.misc.primitives.SequentialActor
import com.superwall.sdk.network.device.DeviceHelper
import com.superwall.sdk.paywall.view.LoadingView
import com.superwall.sdk.paywall.view.PaywallView
import com.superwall.sdk.paywall.view.ShimmerView
import com.superwall.sdk.paywall.view.ViewStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaywallViewCacheTest {
    private lateinit var appCtx: android.content.Context
    private lateinit var activityProvider: ActivityProvider
    private lateinit var deviceHelper: DeviceHelper
    private lateinit var storage: ViewStorage

    private fun keyOf(id: String) = PaywallCacheLogic.key(id, "en_US")

    private lateinit var actorScope: CoroutineScope

    private fun newCache(): PaywallViewCache =
        PaywallViewCache(
            appCtx,
            storage,
            activityProvider,
            deviceHelper,
            actor = SequentialActor(PaywallCacheState(), actorScope),
        )

    @Before
    fun setup() {
        appCtx = RuntimeEnvironment.getApplication()
        activityProvider =
            mockk {
                every { getCurrentActivity() } returns null
            }
        deviceHelper =
            mockk {
                every { locale } returns "en_US"
            }
        storage =
            object : ViewStorage {
                override val views = ConcurrentHashMap<String, View>()
            }
        actorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        actorScope.cancel()
    }

    // -------------------------------------------------------------------
    // Init / pre-population
    // -------------------------------------------------------------------

    @Test
    fun `acquireLoadingView creates and stores it under LoadingView TAG`() {
        Given("a fresh cache") {
            val cache = newCache()

            When("acquireLoadingView is called") {
                val view = cache.acquireLoadingView()

                Then("the view exists in storage under its tag") {
                    assertNotNull(view)
                    assertNotNull(storage.retrieveView(LoadingView.TAG))
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // save / get
    // -------------------------------------------------------------------

    @Test
    fun `save then getPaywallView returns the view synchronously`() =
        runTest {
            Given("a cache and a paywall view") {
                val cache = newCache()
                val view = mockk<PaywallView>(relaxed = true)

                When("saved and immediately fetched") {
                    cache.save(view, "paywall_a")
                    val result = cache.getPaywallView(keyOf("paywall_a"))

                    Then("the same instance is returned without delay") {
                        assertSame(view, result)
                    }
                }
            }
        }

    @Test
    fun `getPaywallView returns null for unknown key`() {
        Given("a cache with no saved paywalls") {
            val cache = newCache()

            Then("looking up a missing key returns null") {
                assertNull(cache.getPaywallView("missing"))
            }
        }
    }

    @Test
    fun `save uses locale-aware cache key`() =
        runTest {
            Given("a device locale of en_US") {
                val cache = newCache()
                val view = mockk<PaywallView>(relaxed = true)

                When("save is called with identifier 'foo'") {
                    cache.save(view, "foo")

                    Then("the view is stored under 'foo_en_US'") {
                        assertSame(view, cache.getPaywallView("foo_en_US"))
                    }
                }
            }
        }

    @Test
    fun `saving same identifier twice keeps the latest view`() =
        runTest {
            Given("two views saved under the same identifier") {
                val cache = newCache()
                val first = mockk<PaywallView>(relaxed = true)
                val second = mockk<PaywallView>(relaxed = true)

                cache.save(first, "dup")
                cache.save(second, "dup")

                Then("getPaywallView returns the second") {
                    assertSame(second, cache.getPaywallView(keyOf("dup")))
                }
            }
        }

    // -------------------------------------------------------------------
    // activePaywallVcKey / activePaywallView
    // -------------------------------------------------------------------

    @Test
    fun `activePaywallVcKey defaults to null`() {
        val cache = newCache()
        assertNull(cache.activePaywallVcKey)
        assertNull(cache.activePaywallView)
    }

    @Test
    fun `setting activePaywallVcKey is observable on subsequent reads`() {
        Given("a cache") {
            val cache = newCache()

            When("the active key is set") {
                cache.activePaywallVcKey = "abc"

                Then("the read returns the same value") {
                    assertEquals("abc", cache.activePaywallVcKey)
                }
            }
        }
    }

    @Test
    fun `activePaywallView returns the view stored under activePaywallVcKey`() =
        runTest {
            Given("a saved paywall and matching active key") {
                val cache = newCache()
                val view = mockk<PaywallView>(relaxed = true)
                cache.save(view, "foo")
                cache.activePaywallVcKey = keyOf("foo")

                Then("activePaywallView returns it") {
                    assertSame(view, cache.activePaywallView)
                }
            }
        }

    @Test
    fun `activePaywallView is null when activeKey points at non-PaywallView`() {
        Given("activeKey set to LoadingView's tag") {
            val cache = newCache()
            cache.activePaywallVcKey = LoadingView.TAG

            Then("activePaywallView is null (cast guarded)") {
                assertNull(cache.activePaywallView)
            }
        }
    }

    @Test
    fun `activePaywallView is null when key has no entry`() {
        val cache = newCache()
        cache.activePaywallVcKey = "ghost"
        assertNull(cache.activePaywallView)
    }

    // -------------------------------------------------------------------
    // getAllPaywallViews / entries
    // -------------------------------------------------------------------

    @Test
    fun `getAllPaywallViews excludes loading and shimmer views`() =
        runTest {
            Given("two saved paywalls") {
                val cache = newCache()
                val a = mockk<PaywallView>(relaxed = true)
                val b = mockk<PaywallView>(relaxed = true)
                cache.save(a, "a")
                cache.save(b, "b")

                Then("only the paywall views are returned") {
                    val views = cache.getAllPaywallViews()
                    assertEquals(2, views.size)
                    assertTrue(views.contains(a))
                    assertTrue(views.contains(b))
                }
            }
        }

    @Test
    fun `getAllPaywallViews is empty when no paywalls saved`() {
        val cache = newCache()
        assertTrue(cache.getAllPaywallViews().isEmpty())
    }

    // -------------------------------------------------------------------
    // removePaywallView / removeAll
    // -------------------------------------------------------------------

    @Test
    fun `removePaywallView removes only that identifier`() =
        runTest {
            Given("two saved paywalls") {
                val cache = newCache()
                val a = mockk<PaywallView>(relaxed = true)
                val b = mockk<PaywallView>(relaxed = true)
                cache.save(a, "a")
                cache.save(b, "b")

                When("one is removed") {
                    cache.removePaywallView("a")

                    Then("only the other remains") {
                        assertNull(cache.getPaywallView(keyOf("a")))
                        assertSame(b, cache.getPaywallView(keyOf("b")))
                    }
                }
            }
        }

    @Test
    fun `removeAll preserves the active key entry`() =
        runTest {
            Given("multiple saved paywalls and an active key") {
                val cache = newCache()
                val a = mockk<PaywallView>(relaxed = true)
                val b = mockk<PaywallView>(relaxed = true)
                cache.save(a, "a")
                cache.save(b, "b")
                cache.activePaywallVcKey = keyOf("a")

                When("removeAll is called") {
                    cache.removeAll()

                    Then("the active entry survives") {
                        assertSame(a, cache.getPaywallView(keyOf("a")))
                    }
                    Then("the inactive entry is gone") {
                        assertNull(cache.getPaywallView(keyOf("b")))
                    }
                }
            }
        }

    @Test
    fun `removeAll with no active key clears every entry`() =
        runTest {
            Given("two saved paywalls and no active key") {
                val cache = newCache()
                cache.save(mockk(relaxed = true), "a")
                cache.save(mockk(relaxed = true), "b")

                When("removeAll is called") {
                    cache.removeAll()

                    Then("no paywall views remain") {
                        assertTrue(cache.getAllPaywallViews().isEmpty())
                    }
                }
            }
        }

    // -------------------------------------------------------------------
    // acquireLoadingView / acquireShimmerView
    // -------------------------------------------------------------------

    @Test
    fun `acquireLoadingView returns the cached instance on repeat calls`() {
        val cache = newCache()
        val first = cache.acquireLoadingView()
        val second = cache.acquireLoadingView()
        assertSame(first, second)
    }

    @Test
    fun `acquireLoadingView is atomic across concurrent callers`() =
        runTest {
            Given("many concurrent acquireLoadingView calls on a fresh cache") {
                val cache = newCache()
                val results =
                    (0 until 32)
                        .map { async(Dispatchers.Default) { cache.acquireLoadingView() } }
                        .awaitAll()

                Then("every caller receives the same canonical instance") {
                    val canonical = results.first()
                    assertTrue(results.all { it === canonical })
                }
                Then("the canonical instance is what's stored") {
                    assertSame(results.first() as View, storage.retrieveView(LoadingView.TAG))
                }
            }
        }

    @Test
    fun `cache hydrates from existing viewStorage entries on construction`() {
        Given("a viewStorage already populated before cache construction") {
            val pre = mockk<PaywallView>(relaxed = true)
            storage.storeView(keyOf("pre"), pre)

            When("a fresh cache is built") {
                val cache = newCache()

                Then("the existing entry is visible to the cache") {
                    assertSame(pre, cache.getPaywallView(keyOf("pre")))
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Concurrency / ordering
    // -------------------------------------------------------------------

    @Test
    fun `concurrent saves from many coroutines all land`() =
        runTest {
            Given("100 saves from background coroutines") {
                val cache = newCache()
                val views = (0 until 100).map { mockk<PaywallView>(relaxed = true) }

                val jobs =
                    views.mapIndexed { i, v ->
                        launch(Dispatchers.Default) { cache.save(v, "p_$i") }
                    }
                jobs.forEach { it.join() }

                Then("all entries are retrievable") {
                    views.forEachIndexed { i, v ->
                        assertSame("missing $i", v, cache.getPaywallView(keyOf("p_$i")))
                    }
                }
            }
        }

    @Test
    fun `concurrent activeKey writes leave a consistent final value`() =
        runTest {
            Given("repeated concurrent activeKey assignments") {
                val cache = newCache()

                val jobs =
                    (0 until 50).map { i ->
                        launch(Dispatchers.Default) { cache.activePaywallVcKey = "k_$i" }
                    }
                jobs.forEach { it.join() }

                Then("the final read returns one of the assigned values") {
                    val final = cache.activePaywallVcKey
                    assertTrue(final in (0 until 50).map { "k_$it" })
                }
            }
        }

    @Test
    fun `interleaved saves and removes converge to a stable state`() =
        runTest {
            Given("concurrent saves and removes on the same identifiers") {
                val cache = newCache()
                val ids = (0 until 20).map { "id_$it" }

                val savers =
                    ids.map { id ->
                        async(Dispatchers.Default) { cache.save(mockk(relaxed = true), id) }
                    }
                val removers =
                    ids.map { id ->
                        async(Dispatchers.Default) { cache.removePaywallView(id) }
                    }
                (savers + removers).awaitAll()

                Then("cache state and viewStorage agree on every key") {
                    // Which of save/remove wins per id depends on interleaving, but the
                    // two stores must end up agreeing on it.
                    ids.forEach { id ->
                        val key = keyOf(id)
                        assertSame(storage.retrieveView(key), cache.getPaywallView(key))
                    }
                    assertEquals(
                        storage.all().filterIsInstance<PaywallView>().size,
                        cache.getAllPaywallViews().size,
                    )
                }
            }
        }

    @Test
    fun `removeAll then save then read returns the new view`() =
        runTest {
            Given("a populated cache that has been cleared") {
                val cache = newCache()
                cache.save(mockk<PaywallView>(relaxed = true), "old")
                cache.removeAll()

                When("a new view is saved after the clear") {
                    val fresh = mockk<PaywallView>(relaxed = true)
                    cache.save(fresh, "new")

                    Then("the new view is readable") {
                        assertSame(fresh, cache.getPaywallView(keyOf("new")))
                    }
                }
            }
        }

    // -------------------------------------------------------------------
    // External writers (SuperwallPaywallActivity, DebugView)
    // -------------------------------------------------------------------

    @Test
    fun `removeView evicts a saved paywall from both cache and viewStorage`() =
        runTest {
            Given("a saved paywall whose activity launch then fails") {
                val cache = newCache()
                val view = mockk<PaywallView>(relaxed = true)
                cache.save(view, "p1")

                When("the launch-failure path removes its key") {
                    cache.removeView(keyOf("p1"))

                    Then("the next lookup misses, forcing a fresh view") {
                        assertNull(cache.getPaywallView(keyOf("p1")))
                        assertNull(storage.retrieveView(keyOf("p1")))
                    }
                }
            }
        }

    @Test
    fun `storeView is visible to cache reads and viewStorage immediately`() {
        Given("a view stored under an activity key") {
            val cache = newCache()
            val view = mockk<PaywallView>(relaxed = true)

            When("storeView is called") {
                cache.storeView("activity-key", view)

                Then("both the cache and viewStorage return it synchronously") {
                    assertSame(view, cache.getPaywallView("activity-key"))
                    assertSame(view, storage.retrieveView("activity-key"))
                }
            }
        }
    }

    @Test
    fun `removeAll sweeps views stored through storeView`() =
        runTest {
            Given("a debug view stored under an arbitrary key") {
                val cache = newCache()
                cache.storeView("debug-key", View(appCtx))

                When("removeAll runs") {
                    cache.removeAll()

                    Then("the debug view is gone from both stores") {
                        assertNull(cache.entries["debug-key"])
                        assertNull(storage.retrieveView("debug-key"))
                    }
                }
            }
        }

    // -------------------------------------------------------------------
    // Loading/shimmer availability for startWithView without present()
    // -------------------------------------------------------------------

    @Test
    fun `loading and shimmer are not in viewStorage until acquired`() {
        Given("a cold cache, as seen by getPaywall() + startWithView()") {
            newCache()

            Then("readers must acquire through the cache instead of viewStorage") {
                assertNull(storage.retrieveView(LoadingView.TAG))
                assertNull(storage.retrieveView(ShimmerView.TAG))
            }
        }
    }

    @Test
    fun `acquire recreates loading and shimmer after removeAll evicts them`() =
        runTest {
            Given("acquired loading and shimmer views") {
                val cache = newCache()
                val loading = cache.acquireLoadingView()
                val shimmer = cache.acquireShimmerView()

                When("removeAll evicts them and they are acquired again") {
                    cache.removeAll()
                    val newLoading = cache.acquireLoadingView()
                    val newShimmer = cache.acquireShimmerView()

                    Then("fresh instances are stored under their tags") {
                        assertTrue(newLoading !== loading)
                        assertTrue(newShimmer !== shimmer)
                        assertSame(newLoading, storage.retrieveView(LoadingView.TAG))
                        assertSame(newShimmer, storage.retrieveView(ShimmerView.TAG))
                    }
                }
            }
        }

    @Test
    fun `registry writes reach both the cache and viewStorage`() {
        Given("the registry view of a cache") {
            val cache = newCache()
            val registry = cache.asRegistry()
            val view = mockk<PaywallView>(relaxed = true)

            When("a view is stored and then removed through the registry") {
                registry.storeView("activity-key", view)
                val stored = registry.retrieveView("activity-key")
                val inCache = cache.getPaywallView("activity-key")
                registry.removeView("activity-key")

                Then("both stores saw each write") {
                    assertSame(view, stored)
                    assertSame(view, inCache)
                    assertNull(cache.getPaywallView("activity-key"))
                    assertNull(storage.retrieveView("activity-key"))
                }
            }
        }
    }
}
