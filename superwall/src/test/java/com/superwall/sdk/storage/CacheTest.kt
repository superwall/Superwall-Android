package com.superwall.sdk.storage

import android.content.Context
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import androidx.core.util.AtomicFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@Serializable
data class TestData(
    val value: String,
    val count: Int,
)

@RunWith(RobolectricTestRunner::class)
class CacheTest {
    private lateinit var context: Context
    private lateinit var cache: Cache
    private lateinit var json: Json
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        json = Json { ignoreUnknownKeys = true }
        cache =
            Cache(
                context,
                ioQueue = testDispatcher,
                json = json,
            )
    }

    @Test
    fun `read returns null when key does not exist`() {
        val storable =
            object : Storable<String> {
                override val key = "non_existent_key"
                override val directory = SearchPathDirectory.CACHE
                override val serializer = serializer<String>()
            }

        val result = cache.read(storable)

        assertNull(result)
    }

    @Test
    fun `write and read stores and retrieves data from memory cache`() {
        val storable =
            object : Storable<TestData> {
                override val key = "test_data"
                override val directory = SearchPathDirectory.CACHE
                override val serializer = serializer<TestData>()
            }

        val testData = TestData("hello", 42)

        cache.write(storable, testData)
        val result = cache.read(storable)

        assertEquals(testData, result)
    }

    @Test
    fun `read retrieves data from disk when not in memory cache`() {
        val storable =
            object : Storable<TestData> {
                override val key = "disk_test_data"
                override val directory = SearchPathDirectory.CACHE
                override val serializer = serializer<TestData>()
            }

        val testData = TestData("from_disk", 99)

        // Write data
        cache.write(storable, testData)

        // Create new cache instance to clear memory cache
        val newCache =
            Cache(
                context,
                ioQueue = testDispatcher,
                json = json,
            )

        // Read should retrieve from disk
        val result = newCache.read(storable)

        assertEquals(testData, result)
    }

    @Test
    fun `delete removes data from memory and disk`() {
        val storable =
            object : Storable<TestData> {
                override val key = "delete_test"
                override val directory = SearchPathDirectory.CACHE
                override val serializer = serializer<TestData>()
            }

        val testData = TestData("to_delete", 123)

        // Write data
        cache.write(storable, testData)

        // Verify it exists
        assertEquals(testData, cache.read(storable))

        // Delete
        cache.delete(storable)

        // Verify it's gone
        assertNull(cache.read(storable))
    }

    @Test
    fun `write overwrites existing data`() {
        val storable =
            object : Storable<String> {
                override val key = "overwrite_test"
                override val directory = SearchPathDirectory.CACHE
                override val serializer = serializer<String>()
            }

        cache.write(storable, "original")
        assertEquals("original", cache.read(storable))

        cache.write(storable, "updated")
        assertEquals("updated", cache.read(storable))
    }

    private fun <T> storable(
        key: String,
        directory: SearchPathDirectory,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) = object : Storable<T> {
        override val key = key
        override val directory = directory
        override val serializer = serializer
    }

    private fun freshCache() = Cache(context, ioQueue = testDispatcher, json = json)

    @Test
    fun `deleted key is not reloaded from a file whose deletion is still queued`() {
        val paused = kotlinx.coroutines.test.StandardTestDispatcher()
        val pausedCache = Cache(context, ioQueue = paused, json = json)
        val key = storable("tombstone_test", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<String>())
        cache.write(key, "on-disk")

        pausedCache.delete(key)

        // File still exists because the delete hasn't run yet
        assertTrue(key.file(context).exists())
        assertNull(pausedCache.read(key))

        paused.scheduler.advanceUntilIdle()
        assertFalse(key.file(context).exists())
    }

    @Test
    fun `write after delete is readable`() {
        val key = storable("delete_then_write", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<String>())
        cache.write(key, "a")
        cache.delete(key)
        cache.write(key, "b")

        assertEquals("b", cache.read(key))
        assertEquals("b", freshCache().read(key))
    }

    @Test
    fun `clearUserScope forgets user scoped data in memory and on disk`() {
        val user = storable("user_key", SearchPathDirectory.USER_SPECIFIC_DOCUMENTS, serializer<String>())
        cache.write(user, "old-user")

        cache.clearUserScope()

        assertNull(cache.read(user))
        assertFalse(user.file(context).exists())
        assertNull(freshCache().read(user))
    }

    @Test
    fun `clearUserScope hides user files never loaded into memory even before they are deleted`() {
        val user = storable("user_unloaded", SearchPathDirectory.USER_SPECIFIC_DOCUMENTS, serializer<String>())
        cache.write(user, "old-user")

        val paused = kotlinx.coroutines.test.StandardTestDispatcher()
        val pausedCache = Cache(context, ioQueue = paused, json = json)
        pausedCache.clearUserScope()

        assertTrue(user.file(context).exists())
        assertNull(pausedCache.read(user))

        paused.scheduler.advanceUntilIdle()
        assertFalse(user.file(context).exists())
    }

    @Test
    fun `writes made after clearUserScope survive the queued deletion`() {
        val paused = kotlinx.coroutines.test.StandardTestDispatcher()
        val pausedCache = Cache(context, ioQueue = paused, json = json)
        val user = storable("user_new_write", SearchPathDirectory.USER_SPECIFIC_DOCUMENTS, serializer<String>())
        pausedCache.write(user, "old-user")

        pausedCache.clearUserScope()
        pausedCache.write(user, "new-user")
        paused.scheduler.advanceUntilIdle()

        assertEquals("new-user", pausedCache.read(user))
        assertEquals("new-user", freshCache().read(user))
    }

    @Test
    fun `clearUserScope keeps app scoped and cache directory data`() {
        val app = storable("app_key", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<String>())
        val cached = storable("cache_key", SearchPathDirectory.CACHE, serializer<String>())
        cache.write(app, "app")
        cache.write(cached, "config")

        cache.clearUserScope()

        assertEquals("app", cache.read(app))
        assertEquals("config", cache.read(cached))
        assertEquals("app", freshCache().read(app))
        assertEquals("config", freshCache().read(cached))
    }

    @Test
    fun `clearUserScope never touches the host app cache directory`() {
        val hostFile = File(context.cacheDir, "host_app_image.png").apply { writeText("host data") }

        cache.clearUserScope()

        assertTrue(hostFile.exists())
        assertEquals("host data", hostFile.readText())
    }

    @Test
    fun `an interrupted write leaves the previous value readable`() {
        val key = storable("atomic_key", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<TestData>())
        cache.write(key, TestData("committed", 1))

        // Simulate a process kill mid-write: a partial temp file next to the committed one.
        val file = key.file(context)
        File(file.path + ".new").writeText("{\"value\":\"half")

        assertEquals(TestData("committed", 1), freshCache().read(key))
    }

    @Test
    fun `reads files written before the switch to AtomicFile`() {
        val key = storable("legacy_key", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<TestData>())
        key.file(context).writeText(json.encodeToString(TestData.serializer(), TestData("legacy", 7)))

        assertEquals(TestData("legacy", 7), freshCache().read(key))
    }

    @Test
    fun `written files are readable through AtomicFile`() {
        val key = storable("atomic_roundtrip", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<String>())
        cache.write(key, "value")

        val raw = AtomicFile(key.file(context)).readFully().toString(Charsets.UTF_8)
        assertEquals("\"value\"", raw)
    }

    @Test
    fun `concurrent reads and writes do not corrupt the memory cache`() {
        val keys = (0 until 50).map { storable("concurrent_$it", SearchPathDirectory.APP_SPECIFIC_DOCUMENTS, serializer<Int>()) }
        val threads =
            (0 until 8).map { t ->
                Thread {
                    repeat(500) { i ->
                        val k = keys[(i + t) % keys.size]
                        if (i % 2 == 0) cache.write(k, i) else cache.read(k)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        keys.forEach { cache.write(it, 1) }
        keys.forEach { assertEquals(1, cache.read(it)) }
    }
}
