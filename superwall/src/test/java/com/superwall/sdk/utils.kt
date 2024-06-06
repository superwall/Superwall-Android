package com.superwall.sdk

fun assertTrue(value: Boolean) {
    if (!value) {
        throw AssertionError("Expected true, got false")
    }
}

fun assertFalse(value: Boolean) {
    if (value) {
        throw AssertionError("Expected false, got true")
    }
}
