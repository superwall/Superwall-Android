package com.superwall.sdk.misc

import java.util.regex.Pattern

fun String.camelCaseToSnakeCase(): String {
    val acronymPattern = "([A-Z]+)([A-Z][a-z]|[0-9])"
    val fullWordsPattern = "([a-z])([A-Z]|[0-9])"
    val digitsFirstPattern = "([0-9])([A-Z])"

    return this.processCamelCaseRegex(acronymPattern)
        ?.processCamelCaseRegex(fullWordsPattern)
        ?.processCamelCaseRegex(digitsFirstPattern)
        ?.lowercase() ?: this.lowercase()
}

private fun String.processCamelCaseRegex(pattern: String): String? {
    val regex = Pattern.compile(pattern)
    val matcher = regex.matcher(this)
    val stringBuffer = StringBuffer()

    while (matcher.find()) {
        matcher.appendReplacement(stringBuffer, matcher.group(1) + "_" + matcher.group(2))
    }

    matcher.appendTail(stringBuffer)
    return stringBuffer.toString()
}