import org.json.JSONObject

interface Trackable {
    // The string representation of the name.
    val rawName: String

    // Any non-superwall parameters that you want to track. Do not include $ signs in parameter names as they will be dropped.
    val customParameters: JSONObject

    // Determines whether the event has the potential to trigger a paywall. Defaults to true.
    val canImplicitlyTriggerPaywall: Boolean

    // Parameters that are marked with a $ when sent back to the server to be recognised as Superwall parameters.
    suspend fun getSuperwallParameters(): JSONObject
}
