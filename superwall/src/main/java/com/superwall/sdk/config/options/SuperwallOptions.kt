package com.superwall.sdk.config.options

import LogLevel
import LogScope
import java.util.*


// Options for configuring Superwall, including paywall presentation and appearance.
//
// Pass an instance of this class to
// ``Superwall/configure(apiKey:purchaseController:options:completion:)-52tke``.
class SuperwallOptions {
    // Configures the appearance and behavior of paywalls.
    var paywalls: PaywallOptions = PaywallOptions()

    // **WARNING**:  The different network environments that the SDK should use.
    // Only use this enum to set ``SuperwallOptions/networkEnvironment-swift.property``
    // if told so explicitly by the Superwall team.

    open class NetworkEnvironment(open val hostDomain: String) {

        open val baseHost: String
            get() = "api.$hostDomain"

        open val collectorHost: String
            get() = "collector.$hostDomain"

        open val scheme: String
            get() = "https"

        open val port: Int?
            get() = null

        // Default: Uses the standard latest environment.
        class Release() : NetworkEnvironment("superwall.me")

        class ReleaseCandidate() : NetworkEnvironment("superwallcanary.com")

        class Developer() : NetworkEnvironment("superwall.dev")

        class Custom(
            override val baseHost: String,
            override val collectorHost: String,
            override val scheme: String,
            override val port: Int?
        ) : NetworkEnvironment(baseHost)
    }


    // **WARNING:**: Determines which network environment your SDK should use.
    // Defaults to `.release`.  You should under no circumstance change this unless you
    // received the go-ahead from the Superwall team.
    var networkEnvironment: NetworkEnvironment = NetworkEnvironment.Release()

    // Enables the sending of non-Superwall tracked events and properties back to the Superwall servers.
    // Defaults to `true`.
    //
    // Set this to `false` to stop external data collection. This will not affect
    // your ability to create triggers based on properties.
    var isExternalDataCollectionEnabled: Boolean = true

    // Sets the device locale identifier to use when evaluating rules.
    //
    // This defaults to the `autoupdatingCurrent` locale identifier. However, you can set
    // this to any locale identifier to override it. E.g. `en_GB`. This is typically used for testing
    // purposes.
    //
    // You can also preview your paywall in different locales using
    // <doc:InAppPreviews>.
    var localeIdentifier: String? = null

    // Forwards events from the game controller to the paywall. Defaults to `false`.
    //
    // Set this to `true` to forward events from the Game Controller to the Paywall via ``Superwall/gamepadValueChanged(gamepad:element:)``.
    var isGameControllerEnabled: Boolean = false

    // Configuration for printing to the console.
    class Logging {
        // Defines the minimum log level to print to the console. Defaults to `warn`.
        var level: LogLevel? = LogLevel.warn

        // Defines the scope of logs to print to the console. Defaults to .all.
        var scopes: EnumSet<LogScope> = EnumSet.of(LogScope.all)
    }

    // The log scope and level to print to the console.
    var logging: Logging = Logging()
}
