# Example App

This app demonstrates how to use Superwall. We've written a mini tutorial below to help you understand what's going on in the app.

Usually, to integrate Superwall into your app, you first need to have configured a paywall using the [Superwall Dashboard](https://superwall.com/dashboard). However, with this example app, we have already done that for you and provided a sample API key to get you up and running. When you integrate the SDK into your own app, you'll need to use your own API key for your own Superwall account. To do that, [sign up for a free account on Superwall](https://superwall.com/sign-up).

## Features

Feature | Sample Project Location 
--- | ---
🕹 Configuring Superwall | [MainActivity.kt](example/app/src/main/java/com/superwall/exampleapp/MainActivity.kt#L54)
👉 Presenting a paywall | [HomeActivity.kt](example/app/src/main/java/com/superwall/exampleapp/HomeActivity.kt#L150)
👥 Identifying account | [MainActivity.kt](example/app/src/main/java/com/superwall/exampleapp/MainActivity.kt#L164)
👥 Resetting account | [HomeActivity.kt](example/app/src/main/java/com/superwall/exampleapp/HomeActivity.kt#L182)

## Requirements

This example app uses:

- Kotlin
- Jetpack Compose
- Android Studio

## Getting Started

Clone or download Superwall from the [project home page](https://github.com/superwall/Superwall-Android). Then, open the folder in Android Studio and take a look at the code inside [example/app/kotlin+java/com.superwall.exampleapp](example/app/src/main/java/com/superwall/exampleapp)`.

You'll see a [ui.theme](example/app/src/main/java/com/superwall/exampleapp/ui/theme) folder relating to the design and components used in the app, which you don't need to worry about.

The [MainActivity.kt](example/app/src/main/java/com/superwall/exampleapp/MainActivity.kt) handles the configuration of the SDK and login, and [HomeActivity.kt](example/app/src/main/java/com/superwall/exampleapp/HomeActivity.kt) handles the presentation of paywalls.

Build and run the app and you'll see the welcome screen:

<p align="center">
  <img src="https://user-images.githubusercontent.com/3296904/161958142-c2f195b9-bd43-4f4e-9521-87c6fe4238ec.png" alt="The welcome screen" width="220px" />
</p>

Superwall is [configured](example/app/src/main/java/com/superwall/exampleapp/MainActivity.kt#L54) on app launch, setting an `apiKey`.

## Logging In

On the welcome screen, enter your name in the **text field**This saves to the Superwall user attributes using [Superwall.instance.setUserAttributes(_:)](example/app/src/main/java/com/superwall/exampleapp/MainActivity.kt#L159). You don't need to set user attributes, but it can be useful if you want to create a rule to present a paywall based on a specific attribute you've set. You can also recall user attributes on your paywall to personalise the messaging.

Tap **Log In**. This identifies the user (with a hardcoded userId that we've set), retrieving any paywalls that have already been assigned to them.

You'll see the home screen:

<p align="center">
  <img src="https://user-images.githubusercontent.com/3296904/161960829-dfdc1319-571a-4784-b18f-bbb8c07f5a65.png" alt="The overview screen" width="220px" />
</p>

## Presenting a Paywall

At the heart of Superwall's SDK lies [Superwall.shared.register(event:params:handler:feature:)](example/app/src/main/java/com/superwall/exampleapp/HomeActivity.kt#L150).

This allows you to register an event to access a feature that may or may not be paywalled later in time. It also allows you to choose whether the user can access the feature even if they don't make a purchase. You can read more about this [in our docs](https://docs.superwall.com/docs).

On the [Superwall Dashboard](https://superwall.com/dashboard) you add this event to a Campaign and attach some presentation rules. For this app, we've already done this for you.

When an event is registered, Superwall evaluates the rules associated with it to determine whether or not to show a paywall.

By calling [Superwall.shared.register(event:params:handler:feature:)](example/app/src/main/java/com/superwall/exampleapp/HomeActivity.kt#L150), you present a paywall in response to the event `campaign_trigger`.

On screen you'll see some explanatory text and a button to launch a feature that is behind a paywall:

<p align="center">
  <img src="https://user-images.githubusercontent.com/3296904/161961942-2b7ccf40-83d1-47c5-8f49-6fb409b17491.png" alt="Presenting a paywall" width="220px" />
</p>

Tap the **Launch Feature** button and you'll see the paywall. If the event is disabled on the dashboard, the paywall wouldn't show and the feature would fire immediately. In this case, the feature is just an alert.

## Purchasing a subscription

Tap the **Continue** button in the paywall and "purchase" a subscription. When the paywall dismisses, the "feature" is launched and you'll see an alert. Try launching the feature again. You'll notice that the feature is fired immediately and no longer shows the paywall. Paywalls are only presented to users who haven't got an active subscription. To cancel the active subscription for an app that's using a StoreKit configuration file for testing, delete and reinstall the app.

## Support

For an in-depth explanation of how to use Superwall, visit our [online docs](https://docs.superwall.com/docs).
