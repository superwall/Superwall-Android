package com.superwall.sdk.storage

interface ReadWriteStorage {

    // Writes to storage without serializing
    fun writeFile(storable: Storable<*>, data: String)
    // Reads from storage without serializing
    fun readFile(storable: Storable<*>): String?
    // Reads from storage and serializes into JSON
    fun <T> get(storable: Storable<T>): T?
    // Serializes into JSON and stores
    fun <T : Any> save(data: T, storable: Storable<T>)
}