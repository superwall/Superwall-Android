# superwall-support (add-on)

The Support add-on for Superwall-Android (plan decision 42): native in-app chat
as its own AAR beside `superwall-compose`, so apps that don't use Support ship
nothing extra. It doesn't depend on `:superwall`; it will talk to core through
`SuperwallSupportHost`, a plain-types bridge found by class name at configure.
Internals sit outside `com.superwall.**`, so core's blanket keep rule doesn't
keep them readable.

**Status: M1, transport only.** No UI, no bridge. Not published: the module
has no maven-publish configuration.

## The transport

`com.superwallsupport.transport.SupportTransport` speaks `superwall-support/1`
(contract: `paywall-next/packages/inbox-contracts`): session start, the newest
window over HTTP, the live WebSocket with resume from the cursor (`prev` gap
checks, duplicates dropped, the tail as truncate-then-append), lenient
decoding (unknown kinds and keys survive, malformed frames ignored), a send
queue persisted to a file and keyed by `clientMessageId`, and attachment
upload (ask, PUT, complete). `SupportLifecycleMonitor` feeds it the process
lifecycle and the network callback.

Rules from the M0 spike: socket only while foregrounded; reconnect at once on
foreground or network-available with backoff reset; ping every 10 s with a 5 s
deadline during catch-up too; when `ready.head - cursor` is over 60, read
HTTP windows instead of a socket replay.

## Why OkHttp

Core uses `HttpURLConnection`, which has no WebSocket. The add-on depends on
OkHttp 4.12 (`implementation`, never exposed in its API):

- validated in the M0 spike on a Pixel 7 Pro (Android 17) and an Android 15
  emulator: `permessage-deflate` negotiated with no code, clean close codes,
  reconnects after network loss
- one client for the socket and the HTTP commands (connection reuse, HTTP/2)
- size: in this module's minified test APK, OkHttp and Okio are 208 of 930
  retained classes; the whole APK (Kotlin, coroutines, serialization, OkHttp,
  the add-on) is 345 KB. Many apps already ship OkHttp (Retrofit, Coil,
  Stripe …), and then it costs nothing extra
- the alternatives: Java-WebSocket is smaller but has no maintained deflate
  and needs its own TLS setup on API 23; a hand-written RFC 6455 client over
  `SSLSocket` means owning TLS, proxies and deflate

Core is untouched: apps without the add-on don't get OkHttp.

## Tests

```sh
./gradlew :superwall-support:testDebugUnitTest
SUPPORT_FIXTURE_URL=http://localhost:8791 ./gradlew :superwall-support:testDebugUnitTest
```

The second needs paywall-next's fixture server
(`PORT=8791 pnpm --filter @superwall/inbox-test-client fixture-server`).
Fixtures come from paywall-next with `scripts/sync-fixtures.sh
/path/to/paywall-next [ref]`, which records the commit in
`src/test/resources/support-fixtures/SOURCE.json`.

`test-app/` drives the transport on an emulator;
`test-app/run-emulator-scenarios.sh <serial> <out>` runs drop-at-every-seq,
airplane mode, background/foreground and far-behind catch-up.
