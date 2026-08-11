package com.superwall.sdk.misc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StringSHA256Test {
    @Test
    fun `sha256Hex hashes the string value deterministically`() {
        assertEquals(
            "fcdec6df4d44dbc637c7c5b58efface52a7f8a88535423430255be0bb89bedd8",
            "user-123".sha256Hex(),
        )
        assertNotEquals("user-123".sha256Hex(), "user-456".sha256Hex())
    }
}
