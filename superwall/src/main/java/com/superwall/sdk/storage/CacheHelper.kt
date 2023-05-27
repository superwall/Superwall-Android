package com.superwall.sdk.storage

// CacheHelper.kt
// CacheDemo
//
// Created by Nguyen Cong Huy on 7/4/16.
// Copyright © 2016 Nguyen Cong Huy. All rights reserved.
//
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.superwall.sdk.storage.memory.GenericCache
import com.superwall.sdk.storage.memory.LRUCache
import com.superwall.sdk.storage.memory.PerpetualCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


sealed class CacheDirectory {
    // Saves to the caches directory, which can be cleared by
    // the system at any time.
    object Cache : CacheDirectory()

    // Specific to the user.
    object UserSpecificDocuments : CacheDirectory()

    // Specific to the app as a whole.
    object AppSpecificDocuments : CacheDirectory()
}


data class CacheHelperConfiguration(
    var memoryCache: GenericCache<String, ByteArray>,
)

class CacheHelper(context: Context, config: CacheHelperConfiguration) {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    private val cacheDir: File = context.cacheDir
    private val userSpecificDocumentDir: File = context.getDir(USER_SPECIFIC_DOCUMENT_DIR, Context.MODE_PRIVATE)
    private val appSpecificDocumentDir: File = context.getDir(APP_SPECIFIC_DOCUMENT_DIR, Context.MODE_PRIVATE)

    // 1000 object LRU cache
    private val memCache: LRUCache<String, ByteArray> = LRUCache(PerpetualCache<String, ByteArray>(), 1000)

    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var maxCachePeriodInSecond: Long = DEFAULT_MAX_CACHE_PERIOD_IN_SECOND
    var maxDiskCacheSize: Long = 0L

    fun <T : StorageConfig> read(config: T): ByteArray? {
        var data = memCache[config.key]

        if (data == null) {
            val targetDirectory =  config.directory
            val file = File(getPathFromConfig(config))
            if (file.exists()) {
                data = file.readBytes()
                memCache[config.key] = data
            }
        }

        return data
    }

    fun <T: StorageConfig> delete(config: T) {
        memCache.remove(config.key)
        val file = File(getPathFromConfig(config))
        file.delete()
    }

    fun <T : StorageConfig> write(config: T, value: ByteArray) {
        memCache[config.key] = value
        val targetDirectory = config.directory
        ioScope.async {
            try {
                val file = File(getPathFromConfig(config))
                file.writeBytes(value)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }


    companion object {
        private const val USER_SPECIFIC_DOCUMENT_DIR = "user_specific_document"
        private const val APP_SPECIFIC_DOCUMENT_DIR = "app_specific_document"
        private const val DEFAULT_MAX_CACHE_PERIOD_IN_SECOND = 60 * 60 * 24 * 7L // a week
    }
    
    
    private fun getPathFromConfig(config: StorageConfig): String {
        val directory = getDirectoryFromCacheDirectory(config.directory)
        return directory.absolutePath + File.separator + getHashedKeyFromConfig(config)
    }

    private fun getDirectoryFromCacheDirectory(directory: CacheDirectory): File {
        return when (directory) {
            CacheDirectory.Cache -> cacheDir
            CacheDirectory.UserSpecificDocuments -> userSpecificDocumentDir
            CacheDirectory.AppSpecificDocuments -> appSpecificDocumentDir
        }
    }

    private fun getHashedKeyFromConfig(config: StorageConfig): String {
        // Create the md5 of the key
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(config.key.toByteArray(Charsets.UTF_8))
        val digest = md5.digest()
        return digest.fold("", { str, it -> str + "%02x".format(it) })
    }
}
