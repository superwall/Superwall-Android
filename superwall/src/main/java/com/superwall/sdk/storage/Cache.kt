package com.superwall.sdk.storage

import android.content.Context
import androidx.core.util.AtomicFile
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.misc.onError
import com.superwall.sdk.utilities.withErrorTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Two-layer key/value store: an in-memory map backed by one file per key.
 *
 * - The memory layer is updated synchronously and is authoritative once a key is in it.
 *   Deletes leave a [Entry.Tombstone] so a read can't reload a file whose deletion is still queued.
 * - Disk writes and deletes run in order on the single-threaded [ioQueue].
 * - Files are written through [AtomicFile], so a process kill mid-write leaves the previous value intact.
 */
class Cache(
    val context: Context,
    private val ioQueue: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
    private val json: Json,
) : CoroutineScope by CoroutineScope(ioQueue) {
    private sealed interface Entry {
        data class Value(
            val data: Any,
            val directory: SearchPathDirectory,
        ) : Entry

        object Tombstone : Entry
    }

    private val memCache = ConcurrentHashMap<String, Entry>()

    // Directories whose files have been (or are queued to be) wiped. For these, any key
    // missing from memCache is known to be absent, so we never read the stale file.
    private val clearedDirectories: MutableSet<SearchPathDirectory> = ConcurrentHashMap.newKeySet()

    fun <T> read(storable: Storable<T>): T? {
        when (val entry = memCache[storable.key]) {
            is Entry.Tombstone -> return null
            is Entry.Value -> {
                @Suppress("UNCHECKED_CAST")
                return entry.data as T
            }
            null -> Unit
        }

        if (storable.directory in clearedDirectories) return null

        val data = readFromDisk(storable) ?: return null
        // Don't clobber a value written (or deleted) while we were reading.
        val existing = memCache.putIfAbsent(storable.key, Entry.Value(data, storable.directory))
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return (existing as? Entry.Value)?.data as? T
        }
        return data
    }

    private fun <T> readFromDisk(storable: Storable<T>): T? {
        var jsonString = ""
        return try {
            jsonString = AtomicFile(storable.file(context)).readFully().toString(Charsets.UTF_8)
            json.decodeFromString(storable.serializer, jsonString)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: Throwable) {
            Logger.debug(
                logLevel = LogLevel.error,
                LogScope.cache,
                message = "Unable to read key: ${storable.key}, got $jsonString",
                error = e,
            )
            null
        }
    }

    fun <T : Any> write(
        storable: Storable<T>,
        data: T,
    ) {
        memCache[storable.key] = Entry.Value(data, storable.directory)

        launch {
            val file = AtomicFile(storable.file(context = context))
            val bytes =
                try {
                    json.encodeToString(storable.serializer, data).toByteArray(Charsets.UTF_8)
                } catch (e: Throwable) {
                    Logger.debug(
                        logLevel = LogLevel.error,
                        LogScope.cache,
                        message = "Unable to encode key: ${storable.key}",
                        error = e,
                    )
                    return@launch
                }
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Throwable) {
                stream?.let { file.failWrite(it) }
                Logger.debug(
                    logLevel = LogLevel.error,
                    LogScope.cache,
                    message = "Unable to write key: ${storable.key}",
                    error = e,
                )
            }
        }
    }

    fun <T : Any> delete(storable: Storable<T>) {
        memCache[storable.key] = Entry.Tombstone
        launch {
            withErrorTracking {
                AtomicFile(storable.file(context = context)).delete()
            }.onError { error ->
                Logger.debug(
                    logLevel = LogLevel.error,
                    LogScope.cache,
                    message = "Unable to delete key: ${storable.key}",
                    error = error,
                )
            }
        }
    }

    //region Clean

    /**
     * Forgets everything stored in [SearchPathDirectory.USER_SPECIFIC_DOCUMENTS].
     *
     * Memory is tombstoned synchronously, so reads immediately return null. The files are
     * deleted on the serial write queue, so any write made after this call is queued after
     * the deletion and survives.
     *
     * Only touches our own private user-specific directory. App-scoped data and the host
     * app's cache directory are left alone.
     */
    fun clearUserScope() {
        val directory = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS
        clearedDirectories.add(directory)
        memCache.entries.forEach { (key, entry) ->
            if (entry is Entry.Value && entry.directory == directory) {
                memCache.replace(key, entry, Entry.Tombstone)
            }
        }

        launch {
            withErrorTracking {
                directory.fileDirectory(context).listFiles()?.forEach { it.delete() }
            }.onError { error ->
                Logger.debug(
                    logLevel = LogLevel.error,
                    LogScope.cache,
                    message = "Unable to clear user-specific storage",
                    error = error,
                )
            }
        }
    }

    //endregion
}
