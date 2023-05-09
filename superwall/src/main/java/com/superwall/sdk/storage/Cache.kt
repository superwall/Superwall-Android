package com.superwall.sdk.storage

// Cache.kt
// CacheDemo
//
// Created by Nguyen Cong Huy on 7/4/16.
// Copyright © 2016 Nguyen Cong Huy. All rights reserved.
//
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface JSONObjectRepresentable {
    fun toJSONObject(): JSONObject
}

class Cache(context: Context) {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    private val cacheDir: File = context.cacheDir
    private val userSpecificDocumentDir: File = context.getDir(USER_SPECIFIC_DOCUMENT_DIR, Context.MODE_PRIVATE)
    private val appSpecificDocumentDir: File = context.getDir(APP_SPECIFIC_DOCUMENT_DIR, Context.MODE_PRIVATE)

    private val memCache: MutableMap<String, ByteArray> = mutableMapOf()

    var maxCachePeriodInSecond: Long = DEFAULT_MAX_CACHE_PERIOD_IN_SECOND
    var maxDiskCacheSize: Long = 0L

    fun <T : Storable> read(keyType: Class<T>, directory: Directory? = null): T? {
        var data = memCache[keyType.simpleName]

        if (data == null) {
            val targetDirectory = directory ?: keyType.newInstance().directory
            val file = File(getDirectory(targetDirectory), keyType.simpleName)
            if (file.exists()) {
                data = file.readBytes()
                memCache[keyType.simpleName] = data
            }
        }

        return data?.let { byteArray ->
            deserialize(byteArray, keyType)
        }
    }

    fun <T : Storable> delete(keyType: Class<T>, directory: Directory? = null) {
        memCache.remove(keyType.simpleName)
        val targetDirectory = directory ?: keyType.newInstance().directory
        val file = File(getDirectory(targetDirectory), keyType.simpleName)
        file.delete()
    }

    fun <T : Storable> write(value: T, directory: Directory? = null) {
        val data = serialize(value)
        memCache[value.javaClass.simpleName] = data

        val targetDirectory = directory ?: value.directory
        executorService.execute {
            try {
                val file = File(getDirectory(targetDirectory), value.javaClass.simpleName)
                file.writeBytes(data)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun getDirectory(directory: Directory): File {
        return when (directory) {
            Directory.CACHE -> cacheDir
            Directory.USER_SPECIFIC_DOCUMENTS -> userSpecificDocumentDir
            Directory.APP_SPECIFIC_DOCUMENTS -> appSpecificDocumentDir
        }
    }


    private fun <T> deserialize(byteArray: ByteArray, type: Class<T>): T? {
        return try {
            val jsonString = String(byteArray, Charsets.UTF_8)
            val jsonObject = JSONObject(jsonString)
            val constructor = type.getDeclaredConstructor(JSONObject::class.java)
            constructor.newInstance(jsonObject)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun <T> serialize(value: T): ByteArray {
        return if (value is JSONObjectRepresentable) {
            value.toJSONObject().toString().toByteArray(Charsets.UTF_8)
        } else {
            throw IllegalArgumentException("The object does not implement JSONObjectRepresentable")
        }
    }

    companion object {
        private const val USER_SPECIFIC_DOCUMENT_DIR = "user_specific_document"
        private const val APP_SPECIFIC_DOCUMENT_DIR = "app_specific_document"
        private const val DEFAULT_MAX_CACHE_PERIOD_IN_SECOND = 60 * 60 * 24 * 7L // a week
    }

    interface Storable {
        val directory: Directory
    }

    enum class Directory {
        CACHE,
        USER_SPECIFIC_DOCUMENTS,
        APP_SPECIFIC_DOCUMENTS
    }
}
