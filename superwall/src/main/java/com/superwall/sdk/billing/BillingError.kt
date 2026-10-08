package com.superwall.sdk.billing

sealed class BillingError(
    val code: Int,
    val description: String,
) : Exception(description) {
    object UnknownError : BillingError(0, "Unknown error.")

    object IllegalStateException : BillingError(1, "IllegalStateException when connecting to billing client")

    // Define a class for custom errors where you can pass a message
    class BillingNotAvailable(
        description: String,
    ) : BillingError(2, description)

    class WithCode(
        code: Int,
        description: String,
    ) : BillingError(code, "Google Billing error: $code - $description")

    /** Google Play did not answer a request in time. Not cached, so a later request retries. */
    class Timeout(
        description: String,
    ) : BillingError(3, description)
}
