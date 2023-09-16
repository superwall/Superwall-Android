import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Date

/**
 * A request to compute a device property associated with an event at runtime.
 */
@Serializable
data class ComputedPropertyRequest(
    val type: ComputedPropertyRequestType,
    val eventName: String
) {
    /**
     * The type of device property to compute.
     */
    enum class ComputedPropertyRequestType(val rawValue: String) {
        MINUTES_SINCE("MINUTES_SINCE"),
        HOURS_SINCE("HOURS_SINCE"),
        DAYS_SINCE("DAYS_SINCE"),
        MONTHS_SINCE("MONTHS_SINCE"),
        YEARS_SINCE("YEARS_SINCE");

        val prefix: String
            get() = when (this) {
                MINUTES_SINCE -> "minutesSince_"
                HOURS_SINCE -> "hoursSince_"
                DAYS_SINCE -> "daysSince_"
                MONTHS_SINCE -> "monthsSince_"
                YEARS_SINCE -> "yearsSince_"
            }

        val calendarComponent: Int
            get() = when (this) {
                MINUTES_SINCE -> Calendar.MINUTE
                HOURS_SINCE -> Calendar.HOUR
                DAYS_SINCE -> Calendar.DAY_OF_MONTH
                MONTHS_SINCE -> Calendar.MONTH
                YEARS_SINCE -> Calendar.YEAR
            }

        fun dateComponent(date: Date): Int {
            val calendar = Calendar.getInstance()
            calendar.time = date
            return calendar.get(calendarComponent)
        }

        companion object {
            fun fromRawValue(value: String): ComputedPropertyRequestType? {
                return values().find { it.rawValue == value }
                    ?: throw IllegalArgumentException("Unsupported computed property type.")
            }
        }
    }
}