package org.server.anonymous.business

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpResultTest {
    @Test
    fun `success carries the value`() {
        val result: OpResult<String> = OpResult.Success("ok")
        assertTrue(result is OpResult.Success<String>)
        assertEquals("ok", (result as OpResult.Success<String>).value)
    }

    @Test
    fun `failure carries the reason`() {
        val result: OpResult<Int> = OpResult.Failure("nope")
        assertTrue(result is OpResult.Failure)
        assertEquals("nope", (result as OpResult.Failure).reason)
    }
}
