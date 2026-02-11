package com.superwall.sdk.store.testmode

sealed class TestModeReason {
    data class ConfigMatch(
        val matchedId: String,
    ) : TestModeReason()

    data class ApplicationIdMismatch(
        val expected: String,
        val actual: String,
    ) : TestModeReason()

    data object DebugOption : TestModeReason()

    val description: String
        get() =
            when (this) {
                is ConfigMatch -> "User ID \"$matchedId\" matched a test mode user in the dashboard config."
                is ApplicationIdMismatch -> "Application ID mismatch: expected \"$expected\", got \"$actual\"."
                is DebugOption -> "Test mode activated via debug option."
            }
}
