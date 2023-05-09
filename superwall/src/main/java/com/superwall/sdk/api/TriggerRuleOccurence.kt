//  File.kt
//  
//
//  Created by Yusuf Tör on 05/07/2022.
//

import org.json.JSONObject

data class TriggerRuleOccurrence(
    val key: String,
    var maxCount: Int,
    val interval: Interval
) {
    enum class IntervalType(val rawValue: String) {
        MINUTES("MINUTES"),
        INFINITY("INFINITY")
    }

    data class RawInterval(
        val type: IntervalType,
        val minutes: Int?
    )

    sealed class Interval {
        object Infinity : Interval()
        data class Minutes(val value: Int) : Interval()
    }

    constructor(key: String, maxCount: Int, rawInterval: RawInterval) : this(
        key,
        maxCount,
        when (rawInterval.type) {
            IntervalType.MINUTES -> {
                val minutes = rawInterval.minutes
                if (minutes != null) Interval.Minutes(minutes) else Interval.Infinity
            }
            else -> Interval.Infinity
        }
    )

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun fromJson(jsonObject: JSONObject): TriggerRuleOccurrence {
            val key = jsonObject.getString("key")
            val maxCount = jsonObject.getInt("max_count")
            val intervalJson = jsonObject.getJSONObject("interval")
            val intervalType = IntervalType.valueOf(intervalJson.getString("type"))
            val minutes = intervalJson.optInt("minutes", -1)

            val rawInterval = RawInterval(
                type = intervalType,
                minutes = if (minutes >= 0) minutes else null
            )

            return TriggerRuleOccurrence(key, maxCount, rawInterval)
        }
    }
}
