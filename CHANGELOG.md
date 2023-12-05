# CHANGELOG

The changelog for `Superwall`. Also see the [releases](https://github.com/superwall-me/Superwall-Android/releases) on GitHub.

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
