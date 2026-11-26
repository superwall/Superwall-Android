## Superwall integration & screenshot tests

This module holds the integration tests that drive the demo app against the live Superwall
backend. Most of them end in a screenshot comparison, made with
[Dropshots](https://github.com/dropbox/dropshots), and the ones about whether a paywall presents
also check the events the SDK reported.

### What the tests need

- **Android 13 (API 33).** The baselines are recorded on it, and system bars differ between
  versions. A test on another version fails straight away with a message saying so.
- **Google Play, signed in to a Google account.** Most paywalls load products from Play. Without an
  account the SDK doesn't present them, and the test fails with the reason, for example
  `The paywall wasn't presented: no_presentation (… No Google account found …)`. So use a
  "Google Play" system image and sign in once, by hand or with `scripts/play_login.sh`, which
  runs the Maestro login flow with `TEST_USER` and `TEST_PW` from the environment or a
  git-ignored `.env` in the repository root.

In CI the tests run on Firebase Test Lab, on a physical Pixel 7 that is already signed in, for
pull requests into `main`. There is also a `Screenshot tests` workflow that runs them on a Google
Play emulator it signs in with that script, but it is manual only: from a GitHub-hosted runner
Google asks the test account for an extra verification step, which the login flow can't answer.
Firebase's virtual devices can't run the tests either: Play billing is unavailable on all three
API 33 models (`MediumPhone.arm`, `SmallPhone.arm`, `MediumTablet.arm`).

### Devices and baselines

A full-screen capture only matches a baseline taken on a device with the same screen size,
density and system bars. So the baselines in `screenshots/` are kept per device profile, and the
profile is picked from the device a test runs on (`ScreenshotDevice`):

| Profile | Baseline prefix | Used by |
| --- | --- | --- |
| `emulator` | `Emulator_Pixel_7_API_33_` | any API 33 emulator: local runs, and the manual `Screenshot tests` workflow |
| `pixel_7` | `Google_Pixel_7_` | a physical Pixel 7, which is what Firebase Test Lab runs in CI |

You don't need a particular emulator or to set it up by hand. `DeviceStateRule` puts the device in
a known state before every test: font scale 1, light theme, no animations, and on an emulator also
gesture navigation and a 1080x2400, 420dpi screen, whatever hardware profile it was created with.
The screen size is put back after the test.

A physical device other than a Pixel 7 has no baselines. To try one anyway, force it to the
emulator's screen with `-Pandroid.testInstrumentationRunnerArguments.screenshotProfile=emulator`.

### Running the tests

```bash
./gradlew :app:connectedDebugAndroidTest
```

To run one class or test, add
`-Pandroid.testInstrumentationRunnerArguments.class=com.example.superapp.test.SimpleScreenshotTestExecutor`
(append `#test_name` for a single test).

### Viewing test results

The report is in `app/build/reports/androidTests/connected`. For every failed comparison,
`app/build/outputs/androidTest-results/connected/debug` holds the screen as it was captured
(`reference/`) and the baseline, the capture and their difference side by side (`diff/`).

### Recording baselines

On a connected device, for its own profile:

```bash
./gradlew :app:recordDebugAndroidTestScreenshots
```

The Pixel 7 baselines can also be taken from a Firebase Test Lab run, without having the phone.
A failed comparison leaves the captured screen in the run's results bucket, so after a run that
failed because the UI changed on purpose:

```bash
scripts/pull_firebase_screenshots.sh gs://<bucket>/<results dir>
```

The path is in the Test Lab output ("Raw results will be stored in your GCS bucket at"). Look at
the images before committing them: this accepts whatever was on screen.

### Writing a test

`screenshotFlow` runs a test case from the demo app and compares the screen after each `step`.
A step doesn't need a sleep before its screenshot: it waits until the screen has stopped changing
(`awaitScreenSettled`), and gives up after five seconds for a screen that never does, such as a
video. For a paywall, `awaitPaywallContentReady()` waits for the page's fonts and images.

A step can also check the recorded events, which says what the SDK did and not only what the
screen looked like:

```kotlin
screenshotFlow(UITestHandler.test0Info) {
    step("", timeline = { assertPaywallPresented() }) {
        it.timeline.awaitPaywallLoaded()
        awaitPaywallContentReady()
    }
}
```

`paywallPresentsFor` and `paywallDoesntPresentFor` do this already. `TimelineExpectations.kt` has
the assertions (`assertContains`, `assertNone`, `assertInOrder`, `assertPaywallPresented`,
`assertPaywallNotPresented`) and two waits: `awaitEvent`, which waits for an event without missing
one that already fired, and `awaitPaywallLoaded`, which fails at once with the reason when the SDK
decides not to present, where waiting for the load event alone would hang until the test times
out. Both the events and the screenshot are checked, and a failure reports both.
