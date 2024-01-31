package com.superwall.sdk.misc

import kotlinx.coroutines.*
import java.util.LinkedList
import java.util.Queue

import kotlinx.coroutines.*
import java.util.*

//class SerialTaskManager(private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
//    private val taskQueue: Queue<suspend () -> Unit> = LinkedList()
//    private var lastTask: Job? = null
//
//    fun addTask(task: suspend () -> Unit) {
//        val newTask = suspend {
//            // Wait for the last task to finish
//            lastTask?.join()
//
//            // Execute the current task
//            task()
//        }
//
//        synchronized(taskQueue) {
//            // Add the new task to the queue
//            taskQueue.offer(newTask)
//
//            // If it's the only task, start executing it
//            if (taskQueue.size == 1) {
//                executeNextTask()
//            }
//        }
//    }
//
//    private fun executeNextTask() {
//        coroutineScope.launch {
//            var nextTask: (suspend () -> Unit)? = null
//
//            synchronized(taskQueue) {
//                nextTask = taskQueue.poll()
//            }
//
//            nextTask?.let { task ->
//                lastTask = launch { task() }
//                lastTask?.join()
//
//                synchronized(taskQueue) {
//                    if (taskQueue.isNotEmpty()) {
//                        executeNextTask()
//                    }
//                }
//            }
//        }
//    }
//}



class SerialTaskManager(private val coroutineScope: CoroutineScope = CoroutineScope(newSingleThreadContext("SerialTaskManager"))) {
    private val taskQueue: Queue<Pair<suspend () -> Unit, String>> = LinkedList()
    private var currentTask: Deferred<Unit>? = null

    fun addTask(task: suspend () -> Unit, event: String) {
        coroutineScope.launch {
            println("*** addTask $event, awaiting")
            // Wait for the current task to finish if there is one
            currentTask?.await()

            println("*** addTask $event, add to queue")
            // Add the task to the queue
            taskQueue.offer(task to event)

            // If there's only one task in the queue, start executing it
            if (taskQueue.size == 1) {
                println("*** addTask $event, immediately execute")
                executeNextTask()
            }
        }
    }

    private suspend fun executeNextTask() {
        // Check if there are tasks in the queue
        if (taskQueue.isEmpty()) {
            return
        }

        // Get the next task from the queue
        val (nextTask, event) = taskQueue.poll() ?: return

        // Run the task and wait for it to complete
        currentTask = coroutineScope.async {
            println("*** Execute: running $event")
            nextTask()
        }
        println("*** Execute: awaiting $event")
        currentTask?.await()

        println("*** Execute: Finished $event")

        // After the task completes, recursively execute the next task
        if (taskQueue.isNotEmpty()) {
            println("*** Execute: get next task, after $event")
            executeNextTask()
        }
    }
}
