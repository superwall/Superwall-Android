package com.superwall.sdk.misc

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

class RequestCoalescence<Input : Any, Output> {
    private val tasks = ConcurrentHashMap<Input, CompletableDeferred<Output>>()

    suspend fun get(input: Input, request: suspend (Input) -> Output): Output {
        val existingTask = tasks[input]
        return if (existingTask != null) {
            // If there's already a task in progress, wait for it to finish
            existingTask.await()
        } else {
            // Start a new task if one isn't already in progress
            val newTask = CompletableDeferred<Output>()
            tasks[input] = newTask
            val output = request(input)
            newTask.complete(output)
            tasks.remove(input)
            output
        }
    }
}