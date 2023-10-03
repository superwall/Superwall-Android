package com.superwall.sdk.misc

import android.graphics.Color

fun Int.isDarkColor(): Boolean {
    val red = Color.red(this)
    val green = Color.green(this)
    val blue = Color.blue(this)

    val lum = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return lum < 0.50
}

fun Int.readableOverlayColor(): Int {
    return if (isDarkColor()) Color.WHITE else Color.BLACK
}

fun Int.inverseReadableOverlayColor(): Int {
    return if (isDarkColor()) Color.BLACK else Color.WHITE
}