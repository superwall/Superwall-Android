<p align="center">
  <img src=".github/assets/readme-banner.png" alt="Superwall" width="100%" />
</p>

<h1 align="center">Superwall for Android</h1>

<p align="center">
  AI-powered paywalls, subscriptions and experiments for Android apps.
</p>

<p align="center">
  <a href="https://github.com/superwall/Superwall-Android/releases">
    <img src="https://img.shields.io/github/v/release/superwall/Superwall-Android?label=SDK&color=555555&labelColor=212223" alt="Latest SDK version" />
  </a>
  <a href="LICENSE.md">
    <img src="https://img.shields.io/badge/License-MIT-555555?labelColor=212223" alt="MIT license" />
  </a>
  <a href="https://github.com/superwall/Superwall-Android/actions">
    <img src=".github/badges/jacoco.svg" alt="Code coverage" />
  </a>
</p>

<p align="center">
  <a href="#add-superwall-with-a-single-prompt">Get started</a> ·
  <a href="#manual-quick-start">Manual setup</a> ·
  <a href="https://superwall.com/docs/android">Docs</a> ·
  <a href="#examples">Examples</a>
</p>

## Add Superwall with a single prompt

Paste this into your coding agent. It installs the SDK, sets up entitlements and adds your first paywall.

```text
Install Superwall into my app using the skill at github.com/superwall/skills
```

Prefer a guided setup? Run this from your app’s folder. It signs you up or in, then runs Claude Code or Codex for you:

```bash
npx superwall integrate
```

Read the [agent integration guide](https://superwall.com/docs/agents/integrate-the-sdk) for details.

<details>
<summary>Moving from a different platform?</summary>

Run this from your app’s folder. It follows the same steps, starting from your existing setup:

```bash
npx superwall migrate
```

</details>

## Manual quick start

Install and configure the SDK yourself. Requires `minSdk` 23 or later.

### 1. Install the SDK

Add the dependency to your app’s `build.gradle.kts`, using the [latest version](https://github.com/superwall/Superwall-Android/releases):

```kotlin
implementation("com.superwall.sdk:superwall-android:<latest-version>")
```

Then add the permissions and the paywall activity to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="com.android.vending.BILLING" />

<application ...>
  <activity
    android:name="com.superwall.sdk.paywall.view.SuperwallPaywallActivity"
    android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar"
    android:configChanges="orientation|screenSize|keyboardHidden" />
  <activity android:name="com.superwall.sdk.debug.DebugViewActivity" />
  <activity android:name="com.superwall.sdk.debug.localizations.SWLocalizationActivity" />
  <activity android:name="com.superwall.sdk.debug.SWConsoleActivity" />
</application>
```

<details>
<summary>Using a version catalog or Groovy</summary>

`libs.versions.toml`:

```toml
[libraries]
superwall-android = { group = "com.superwall.sdk", name = "superwall-android", version = "<latest-version>" }
```

Groovy `build.gradle`:

```groovy
implementation "com.superwall.sdk:superwall-android:<latest-version>"
```

</details>

### 2. Configure the SDK

[Create a Superwall account](https://superwall.com/sign-up) and copy your app’s public API key from the dashboard. Configure Superwall once, in your `Application`:

```kotlin
class MainApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    Superwall.configure(this, "YOUR_PUBLIC_API_KEY")
  }
}
```

### 3. Register a placement

A placement is a named point in your app where a campaign can show a paywall. On the dashboard, add the `premium_feature` placement to a campaign and give it a paywall. Then call this where someone opens the feature:

```kotlin
Superwall.instance.register("premium_feature") {
  // Open your feature here.
}
```

The campaign decides whether a paywall shows. With no paywall set up, the feature runs straight away. See the [Android guide](https://superwall.com/docs/android) for purchases and testing. Test on a device or emulator with the Play Store, signed in to a Google account.

## Platforms

| Platform | Support |
| --- | --- |
| Android | `minSdk` 23+ · Kotlin and Java · Views and Jetpack Compose |
| Jetpack Compose | `com.superwall.sdk:superwall-compose` for `PaywallComposable` |
| Billing | Google Play Billing, or your own purchase controller |

## What you get with Superwall

- **Paywalls and onboarding flows:** build single paywalls or multi-page onboarding in the dashboard and publish changes without an app release.
- **Experiments:** target audiences with campaigns and A/B test paywalls, prices and flows.
- **Web checkout and funnels:** sell through Stripe on the web and share web funnels as links.
- **Subscriptions and entitlements:** Google Play Billing purchases with server-side validation, or bring your own purchase controller.
- **Retention:** free trial reminders, cancellation surveys, abandoned-purchase offers and win-back paywalls.
- **Customer Center:** a ready-made screen where users manage their subscription.
- **Revenue analytics:** conversions, trials, MRR, churn and LTV, also available over SQL and webhooks.
- **Agent tooling:** skills, an MCP server and a CLI so your coding agent can set up and run Superwall.

## Examples

- [Example app](example): configuring Superwall, presenting a paywall, and identifying and resetting users.

## Upgrading

See the [release notes](https://github.com/superwall/Superwall-Android/releases) and the [changelog](CHANGELOG.md) for changes between versions.

## Support and contributing

[Read the docs](https://superwall.com/docs/android) for setup help, or [report a bug](https://github.com/superwall/Superwall-Android/issues) with your SDK version and steps to reproduce it. To contribute, read the [contributing guide](.github/CONTRIBUTING.md).

Superwall for Android is available under the [MIT license](LICENSE.md).
