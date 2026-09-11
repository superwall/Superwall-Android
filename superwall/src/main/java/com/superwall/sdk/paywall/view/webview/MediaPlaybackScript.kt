package com.superwall.sdk.paywall.view.webview

/** Keeps hidden paywall media paused without suspending JavaScript needed for preloading. */
internal object MediaPlaybackScript {
    fun build(allowed: Boolean): String =
        """
        (() => {
            const key = '__swMediaPlayback';
            if (!window[key]) {
                const state = { allowed: false, suspended: new Set() };
                state.pause = media => {
                    if (!media.paused && !media.ended) {
                        state.suspended.add(media);
                        media.pause();
                    }
                };
                // Capture also catches autoplay and media inserted after the initial scan.
                document.addEventListener('play', event => {
                    if (!state.allowed && event.target instanceof HTMLMediaElement) {
                        state.pause(event.target);
                    }
                }, true);
                window[key] = state;
            }
            const state = window[key];
            state.allowed = $allowed;
            if (!state.allowed) {
                document.querySelectorAll('video, audio').forEach(state.pause);
            } else {
                const suspended = Array.from(state.suspended);
                state.suspended.clear();
                suspended.forEach(media => {
                    if (media.isConnected && !media.ended) {
                        const result = media.play();
                        if (result) result.catch(() => {});
                    }
                });
            }
        })();
        """.trimIndent()
}
