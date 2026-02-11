package com.superwall.sdk.storage

import java.io.FileInputStream
import java.io.FileOutputStream

interface Storage {
    fun <T> read(storable: Storable<T>): T?

    fun <T : Any> write(
        storable: Storable<T>,
        data: T,
    )

    fun <T : Any> writeFile(
        storable: Storable<T>,
        data: String,
    )

    fun <T : Any> getFileStream(storable: Storable<T>): FileOutputStream

    fun <T : Any> readFile(storable: Storable<T>): String?

    fun <T : Any> delete(storable: Storable<T>) {
    }

    fun <T : Any> readFileStream(storable: Storable<T>): FileInputStream = throw NotImplementedError("")

    fun clean()
}
