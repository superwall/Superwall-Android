package com.superwall.sdk.game

import com.google.gson.GsonBuilder

data class GameControllerEvent(
    var eventName: String = "game_controller_input",
    var controllerElement: String,
    var value: Double,
    var x: Double,
    var y: Double,
    var directional: Boolean,
) {
    val jsonString: String?
        get() {
            val gson =
                GsonBuilder()
                    .setFieldNamingStrategy { f ->
                        convertToSnakeCase(f.name)
                    }.create()
            return try {
                gson.toJson(this)
            } catch (e: Throwable) {
                null
            }
        }

    private fun convertToSnakeCase(input: String): String = input.replace(Regex("([a-z])([A-Z]+)"), "$1_$2").lowercase()
}
