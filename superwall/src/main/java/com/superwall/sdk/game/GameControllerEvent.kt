package com.superwall.sdk.game

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.lang.Exception

data class GameControllerEvent(
    var eventName: String = "game_controller_input",
    var controllerElement: String,
    var value: Double,
    var x: Double,
    var y: Double,
    var directional: Boolean
) {
    val jsonString: String?
        get() {
            val gson = GsonBuilder().setFieldNamingStrategy { f ->
                convertToSnakeCase(f.name)
            }.create()
            return try {
                gson.toJson(this)
            } catch (e: Exception) {
                null
            }
        }

    private fun convertToSnakeCase(input: String): String {
        return input.replace(Regex("([a-z])([A-Z]+)"), "$1_$2").lowercase()
    }
}
