package org.server.anonymous.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmojiImagesTest {
    private fun values(text: String): List<String> =
        EmojiImages.split(text).map { segment ->
            when (segment) {
                is EmojiImages.Segment.Text -> segment.value
                is EmojiImages.Segment.Emoji -> segment.value
            }
        }

    @Test
    fun `every palette emoji has a baked color asset`() {
        val missing = EmojiImages.emojis.filter { EmojiImages.assetOf(it) == null }
        assertTrue(missing.isEmpty(), "emoji assets missing: $missing")
    }

    @Test
    fun `split separates text and emoji runs`() {
        assertEquals(listOf("hi ", "😀", " there ", "❤️"), values("hi 😀 there ❤️"))
    }

    @Test
    fun `split leaves plain text as one run`() {
        assertEquals(listOf("plain message"), values("plain message"))
    }

    @Test
    fun `split keeps adjacent emojis apart`() {
        assertEquals(2, EmojiImages.split("😀😀").count { it is EmojiImages.Segment.Emoji })
    }

    @Test
    fun `split treats invite strings as plain text`() {
        val body = "inv4p:abc"
        assertEquals(listOf(body), values(body))
    }
}
