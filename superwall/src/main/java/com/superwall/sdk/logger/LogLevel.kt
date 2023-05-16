
enum class LogLevel(val level: Int) {
    debug(10),
    info(20),
    warn(30),
    error(40);

    override fun toString(): String {
        return when (this) {
            debug -> "DEBUG"
            info -> "INFO"
            warn -> "WARN"
            error -> "ERROR"
        }
    }

    fun getDescriptionEmoji(): String {
        return when (this) {
            debug -> "💬"
            info -> "ℹ️"
            warn -> "⚠️"
            error -> "‼️"
        }
    }
}
