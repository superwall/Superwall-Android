<p align="center">
  <br />
  <img src=https://user-images.githubusercontent.com/3296904/158817914-144c66d0-572d-43a4-9d47-d7d0b711c6d7.png alt="logo" height="100px" />
  <h3 style="font-size:26" align="center">In-App Paywalls Made Easy 💸</h3>
  <br />
</p>

<p align="center">
  <a href="https://superwall.com/docs/installation-via-gradle">
    <img src="https://img.shields.io/badge/Maven-Compatible-orange" alt="Maven Compatible">
  </a>
  <a href="https://superwall.com/docs/installation-via-gradle">
    <img src="https://img.shields.io/badge/gradle-compatible-informational" alt="Gradle Compatible">
  </a>
  <a href="https://github.com/superwall/Superwall-Android/blob/main/LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-green/" alt="MIT License">
  </a>
  <a href="https://superwall.com/">
    <img src="https://img.shields.io/badge/community-active-9cf" alt="Community Active">
  </a>
  <a href="https://superwall.com/">
    <img src="https://img.shields.io/github/v/tag/superwall/Superwall-Android" alt="Version Number">
  </a>
  <a href="https://github.com/superwall/Superwall-Android/actions">
    <img src=".github/badges/jacoco.svg" alt="Code Coverage">
  </a>
</p>

----------------

[Superwall](https://superwall.com/) lets you remotely configure every aspect of your paywall — helping you find winners quickly.

## Superwall

**Superwall** is an open source framework that provides a wrapper around `WebView` for presenting and creating paywalls. It interacts with the Superwall backend letting you easily iterate paywalls on the fly in `Kotlin` or `Java`!


## Features
|   | Superwall |
| --- | --- |
✅ | Server-side paywall iteration
🎯 | Paywall conversion rate tracking - know whether a user converted after seeing a paywall
🆓 | Trial start rate tracking - know and measure your trial start rate out of the box
📊 | Analytics - automatic calculation of metrics like conversion and views
✏️ | A/B Testing - automatically calculate metrics for different paywalls
📝 | [Online documentation](https://superwall.com/docs/android-beta) up to date
🔀 | [Integrations](https://superwall.com/docs/android-beta) - over a dozen integrations to easily send conversion data where you need it
💯 | Well maintained - [frequent releases](https://github.com/superwall/Superwall-Android/releases)
📮 | Great support - email a founder: jake@superwall.com

## Installation

### Gradle

> For a more complete instruction set, [visit our docs](https://superwall.com/docs/installation-via-gradle). 

The preferred installation method is with [Gradle](https://superwall.com/docs/installation-via-gradle). This is a tool for automating the distribution of Kotlin/Java code and is integrated into the Android Studio compiler. In Android Studio, do the following:

- Open **build.gradle**
- Add `implementation "com.superwall.sdk:superwall-android:<INSERT-LATEST-VERSION>"` [latest version](https://github.com/superwall/Superwall-Android/releases)
- Make sure you press `Sync Now`
- Edit your **AndroidManifest.xml** by adding:
```xml
<manifest ...>

<!-- (1) Add theses lines -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="com.android.vending.BILLING" />

<application ...>

  <!-- (2) Add these lines -->
  <activity
    android:name="com.superwall.sdk.paywall.view.SuperwallPaywallActivity"
    android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar"
    android:configChanges="orientation|screenSize|keyboardHidden">
  </activity>
  <activity android:name="com.superwall.sdk.debug.DebugViewActivity" />
  <activity android:name="com.superwall.sdk.debug.localizations.SWLocalizationActivity" />
  <activity android:name="com.superwall.sdk.debug.SWConsoleActivity" />
```
- Start configuring the SDK 👇 

## Getting Started

[Sign up for a free account on Superwall](https://superwall.com/sign-up) and [read our docs](https://superwall.com/docs/android-beta).

<!-- TODO: Re-enable this once we have the example apps -->
<!--
You can also [view our iOS SDK docs](https://sdk.superwall.me/documentation/superwallkit/). If you'd like to view it in Xcode, select **Product ▸ Build Documentation**.

Read our Kodeco (previously Ray Wenderlich) tutorial: [Superwall: Remote Paywall Configuration on iOS](https://www.kodeco.com/38677971-superwall-remote-paywall-configuration-on-ios).

Check out our sample apps for a hands-on demonstration of the SDK:

- [Swift-UIKit with RevenueCat Example App](Examples/UIKit+RevenueCat)
- [Swift-UIKit with StoreKit Example App](Examples/UIKit-Swift)
- [SwiftUI Example App](Examples/SwiftUI)
- [Objective-C-UIKit](Examples/UIKit+RevenueCat)
-->

## Contributing

Please see the [CONTRIBUTING](.github/CONTRIBUTING.md) file for how to help.

### Device IP observations

During enrichment, the SDK also starts a best-effort request to
`https://v4.superwall-enrichment.com/api/v1/enrich`. It sends no user attributes or API key
to this endpoint. The existing enrichment API continues to provide geo and demand scoring.
It must also return the observed `ipV4` or `ipV6` and corresponding ISO 8601
`ipV4ObservedAt` / `ipV6ObservedAt` timestamp to capture that connection's address.

The SDK exposes `ipV4`, `ipV6`, `ipV4ObservedAt`, and `ipV6ObservedAt` as device
attributes when available. They remain separate: an IPv4 response does not erase IPv6.
IPv6 is opportunistic; a dual-stack enrichment request can use IPv4 even on a device
with IPv6. These are public network egress addresses, potentially shared through NAT or VPN.

The extra request never blocks configuration or purchases. Attempts are coalesced and
limited to one per 15 minutes when enrichment runs. The IPv4 collector is session-local; the existing enrichment cache may restore a still-fresh
observation after relaunch. All observations are omitted from device attributes after 15
minutes; network changes can make them stale sooner.
They are not proof of identity. Downstream consumers must preserve the timestamps and apply
their own freshness policy. Wrapper SDKs receive this behavior when they adopt a native
SDK release containing it.
