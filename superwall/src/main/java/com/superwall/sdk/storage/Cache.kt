package com.superwall.sdk.storage

import android.content.Context
import com.superwall.sdk.logger.LogLevel
import com.superwall.sdk.logger.LogScope
import com.superwall.sdk.logger.Logger
import com.superwall.sdk.storage.memory.LRUCache
import com.superwall.sdk.storage.memory.PerpetualCache
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

class Cache(
    val context: Context,
    private val ioQueue: ExecutorCoroutineDispatcher = newSingleThreadContext(Cache.ioQueuePrefix)
) {
    companion object {
        private const val userSpecificDocumentDirectoryPrefix =
            "com.superwall.document.userSpecific.Store"
        private const val appSpecificDocumentDirectoryPrefix =
            "com.superwall.document.appSpecific.Store"
        private const val cacheDirectoryPrefix = "com.superwall.cache.Store"
        private const val ioQueuePrefix = "com.superwall.queue.Store"
        private const val defaultMaxCachePeriodInSecond: Long = 60 * 60 * 24 * 7 // a week
    }

//    private val cacheUrl: File?
//    private val userSpecificDocumentUrl: File?
//    private val appSpecificDocumentUrl: File?
    private val memCache: LRUCache<String, Any> =
        LRUCache(PerpetualCache<String, Any>(), 1000)
//    private val fileManager = File(".") // Placeholder for FileManager

    /** Life time of disk cache, in second. Default is a week */
//    private var maxCachePeriodInSecond = Cache.defaultMaxCachePeriodInSecond

    /** Size is allocated for disk cache, in byte. 0 mean no limit. Default is 0 */
//    private var maxDiskCacheSize: UInt = 0u

    init {
//        val cacheDir: File = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS.directory(context)
//        val userSpecificDocumentDir: File = SearchPathDirectory.USER_SPECIFIC_DOCUMENTS.directory(context)
//        val appSpecificDocumentDir: File = SearchPathDirectory.APP_SPECIFIC_DOCUMENTS.directory(context)

//        cacheUrl = fileManager.getCacheDirectory()?.resolve(Cache.cacheDirectoryPrefix)
//        userSpecificDocumentUrl = fileManager.getDocumentDirectory()?.resolve(Cache.userSpecificDocumentDirectoryPrefix)
//        appSpecificDocumentUrl = fileManager.getDocumentDirectory()?.resolve(Cache.appSpecificDocumentDirectoryPrefix)

        // TODO: clear expired entries from disk cache when backgrounding/terminating
    }

    fun <T> readFile(storable: Storable<T>): String? {
        val file = File(storable.path(context = context))
        return if (file.exists()) {
            file.readText(Charsets.UTF_8)
        } else {
            null
        }
    }

    fun <T> writeFile(storable: Storable<T>, contents: String): Boolean? {
        val file = File(storable.path(context = context))
        return try {
            file.writeText(contents, Charsets.UTF_8)
            true
        }catch (e: Throwable){
            Logger.debug(
                logLevel = LogLevel.info,
                scope = LogScope.cache,
                "Cannot write file ${file.path}"
            )
            false
        }
    }


    fun <T> read(storable: Storable<T>): T? {
        var data = memCache[storable.key] as? T

        if (data == null) {
            runBlocking(ioQueue) {
                val file = File(storable.path(context = context))
                if (file.exists()) {
                    var jsonString = ""
                    try {
                        jsonString = file.readText(Charsets.UTF_8)
                        data = Json.decodeFromString(storable.serializer, jsonString)
                        data?.let {
                            memCache[storable.key] = it
                        }
                    } catch (e: Throwable) {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            LogScope.cache,
                            message = "Unable to read key: ${storable.key}, got $jsonString",
                            error = e
                        )
                    }

                }
            }
        }

        return data
    }

    fun <T: Any> write(storable: Storable<T>, data: T) {
        memCache[storable.key] = data

        GlobalScope.launch(ioQueue) {
            val file = File(storable.path(context = context))
            val jsonString = Json.encodeToString(storable.serializer, data)
            file.writeText(jsonString, Charsets.UTF_8)
        }
    }

    fun <T : Any> delete(storable: Storable<T>) {
        memCache.remove(storable.key)

        GlobalScope.launch(ioQueue) {
            val file = File(storable.path(context = context))
            if (file.exists()) {
                file.delete()
            }
        }
    }

    //region Clean

    fun cleanUserFiles() {
        memCache.clear()
        cleanDiskCache()
    }

    private fun cleanDiskCache() {
        // TODO:
    }

    //endregion
}