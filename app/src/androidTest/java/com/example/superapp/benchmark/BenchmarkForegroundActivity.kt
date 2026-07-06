package com.example.superapp.benchmark

import android.app.Activity

/**
 * Bare foreground holder for the preload benchmark.
 *
 * The SDK's config fetch gates on the app being foregrounded, but launching the
 * real MainActivity pulls in appcompat, which lazily configures EmojiCompat and
 * opens a content-provider connection to Play Services' FontsProvider. GMS
 * restarts itself shortly after boot on CI Play images, and the OS kills any
 * app holding a provider connection to a dying process — which killed every
 * benchmark run mid-measurement. A plain framework Activity with no content
 * keeps the process foregrounded without touching GMS at all.
 */
class BenchmarkForegroundActivity : Activity()
