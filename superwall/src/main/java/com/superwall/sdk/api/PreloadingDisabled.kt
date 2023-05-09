import org.json.JSONObject

data class PreloadingDisabled(
    val all: Boolean,
    val triggers: Set<String>
) {
    companion object {
        fun fromJson(jsonObject: JSONObject): PreloadingDisabled {
            val all = jsonObject.getBoolean("all")
            val triggersJsonArray = jsonObject.getJSONArray("triggers")
            val triggers = mutableSetOf<String>()
            for (i in 0 until triggersJsonArray.length()) {
                triggers.add(triggersJsonArray.getString(i))
            }

            return PreloadingDisabled(all, triggers)
        }
    }
}
