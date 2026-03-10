package com.superwall.sdk.misc.primitives

import com.superwall.sdk.misc.engine.SdkEvent

internal open class Reducer<S>(
    open val applyOn: Fx.(S) -> S,
) : SdkEvent
