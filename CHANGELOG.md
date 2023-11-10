# CHANGELOG

The changelog for `Superwall`. Also see the [releases](https://github.com/superwall-me/Superwall-Android/releases) on GitHub.

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
