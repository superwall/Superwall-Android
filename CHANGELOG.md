# CHANGELOG

The changelog for `Superwall`. Also see the [releases](https://github.com/superwall-me/Superwall-Android/releases) on GitHub.

## 1.0.0-alpha.37

### Enhancements

- SW-2684: Adds error logging if the `currentActivity` is `null` when trying to present a paywall.

### Fixes

- Fixes bug where paywalls might not present on first app install.
- Fixes bug where all `paywallResponseLoad_` events were being counted as `paywallResponseLoad_start`.
- Adds ProGuard rule to prevent `DefaultLifecycleObserver` from being removed.

## 1.0.0-alpha.36

### Enhancements

- Adds `X-Subscription-Status` header to all requests.
- Caches the last `subscriptionStatus`.
- Adds `subscriptionStatus_didChange` event that is fired whenever the subscription status changes.
- Calls the delegate method `subscriptionStatusDidChange` whenever the subscription status changes.
- SW-2676: Adds a completion block to the `configure` method.

### Fixes

- Fixes issue where the main thread was blocked when accessing some properties internally.
- SW-2679: Fixes issue where the `subscription_start` event was being fired even if a non-recurring product was
purchased.

## 1.0.0-alpha.35

### Fixes

- Fixes issue where `transaction_complete` events were being rejected by the server.

## 1.0.0-alpha.34

### Breaking Changes

- Changes `Superwall.instance.getUserAttributes()` to `Superwall.instance.userAttributes`.
- `SuperwallOptions.logging.logLevel` is now non-optional. Set it to `LogLevel.none` to prevent
logs from being printed to the console.

### Enhancements

- SW-2663: Adds `preloadAllPaywalls()` and `preloadPaywalls(eventNames:)` to be able to manually 
preload paywalls.
- SW-2665: Adds `Superwall.instance.userId` so you can access the current user's id.
- SW-2668: Adds `preferredLocale` and `preferredLanguageLocale` to the device attributes for use in rules.
- Adds `Superwall.instance.logLevel` as a convenience variable to set and get the log level.

### Fixes

- SW-2664: Fixes race condition between resetting and presenting paywalls.

## 1.0.0-alpha.33

### Fixes

- Fixes issue where a user would be asked to enable notifications even if there weren't any attached 
to the paywall.

## 1.0.0-alpha.32

### Enhancements

- SW-2214: Adds ability to use free trial notifications with a paywall.
- Adds `cancelAllScheduledNotifications()` to cancel any scheduled free trial notifications.
- SW-2640: Adds `computedPropertyRequests` to `PaywallInfo`.
- SW-2641: Makes `closeReason` in `PaywallInfo` non-optional.

### Fixes

- Fixes issue where thrown exceptions weren't always being caught.

## 1.0.0-alpha.31

### Enhancements

- SW-2638: Adds `Restored` to `PurchaseResult`.
- SW-2644: Adds `RestoreType` to `SuperwallEvent.TransactionRestore`.
- SW-2643: Makes `storePayment` non-optional for a `StoreTransaction`.
- SW-2642: Adds `productIdentifier` to `StorePayment`.

### Fixes

- SW-2635: Fixes crash that sometimes occurred if an app was trying to get Superwall's paywall 
configuration in the background.

## 1.0.0-alpha.30

### Enhancements

- SW-2154: The SDK now includes a paywall debugger, meaning you can scan the QR code of any paywall in the 
editor to preview it on device. You can change its localization, view product attributes, and view
the paywall with or without a trial.

### Fixes

- More bug fixes relating to the loading of products.

## 1.0.0-alpha.29

### Fixes

- SW-2631: Fixes issue where paywalls weren't showing if the products within them had a base plan or
offer ID set.

## 1.0.0-alpha.28

### Fixes

- SW-2615: Fixes crash on Android versions <8 when accessing the Android 8+ only class Period.
- SW-2616: Fixes crash where the `PaywallViewController` was sometimes being added to a new parent view before
being removed from it's existing parent view.

## 1.0.0-alpha.27

### Breaking Changes

- #SW-2218: Changes the `PurchaseController` purchase function to `purchase(activity:productDetails:basePlanId:offerId:)`. 
This adds support for purchasing offers and base plans.

### Enhancements

- #SW-2600: Backport device attributes
- Adds support for localized paywalls.
- Paywalls are only preloaded if their associated rules can match.
- Adds status bar to full screen paywalls.

### Fixes

- Fixes issue where holdouts were still matching even if the limit set for their corresponding rules were exceeded.
- #SW-2584: Fixes issue where prices with non-terminating decimals were causing products to fail to load.

## 1.0.0-alpha.26

### Fixes
- Additional fixes to make Google billing more robust.
- Fixes an issue that causes `transaction_complete` events to fail.

## 1.0.0-alpha.25

### Fixes 

- Fixes [Google Billing Crash on Samsung devices](https://community.revenuecat.com/sdks-51/how-to-fix-crash-too-many-bind-requests-999-for-service-intent-inappbillingservice-3317). 

## 1.0.0-alpha.24

### Fixes

- Fixes an issue that could cause "n/a" to be displayed on a paywall in place of the proper subscription period string.

## 1.0.0-alpha.23

### Fixes

- Fixes an issue where calling `identify` right after `configure` would hang b/c network requests need to access the user id to add to headers. 
- Fixes a potential crash when loading data from disk 

## 1.0.0-alpha.22

### Fixes

- Fixes threading issue

## 1.0.0-alpha.21

### Fixes

- Changes Activity to AppCompatActivity

## 1.0.0-alpha.20

### Enhancements

### Fixes

- Fixes `app_open` race condition
- Prevents calling purchase twice
- Disables interactivity during purchase

## 1.0.0-alpha.19

### Fixes

- Fixes `app_launch` event not triggering

## 1.0.0-alpha.18

### Enhancements

- Adds the ability to provide an `ActivityProvider` when configuring the SDK. This is an interface 
containing the function `getCurrentActivity()`, which Superwall will use to present paywalls from. 
You would typically conform an `ActivityLifecycleTracker` to this interface.

### Fixes

- Fixes a crash when storing a `List` to user attributes and if that List or a Map had a null value.

## 1.0.0-alpha.17

### Enhancements

- Adds automatic purchase controller
- Improves memory handling for webviews
- Hides the loading indicator on a paywall if transactionBackgroundView is set to NONE

## 1.0.0-alpha.14

### Enhancements

- Adds `trigger_session_id` to Superwall Events.
- Resets the scroll position of the paywall on close.

### Fixes

- Fixes issue where an invalid currency code on a device would crash the app when trying to retrieve products.

## 1.0.0-alpha.13

### Fixes

- Fixes concurrency issues when setting and retrieving values like the appUserId and seed.

## 1.0.0-alpha.11

### Fixes

- Can now use both non-recurring products and subscription products in paywalls.
- Fixes a crash issue that was caused by a lazy variable being accessed before it was initialized.
