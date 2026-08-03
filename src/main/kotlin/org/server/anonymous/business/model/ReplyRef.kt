package org.server.anonymous.business.model

/**
 * A reference to the message being replied to. Carries the original author's static key
 * (empty in 1:1 chats when the author is the peer and the key was never bound), the
 * display name the replying user saw (fallback when the key can't be resolved later),
 * and a truncated single-line preview of the original text.
 */
data class ReplyRef(
    val senderKey: ByteArray? = null,
    val senderName: String? = null,
    val text: String,
) {
    companion object {
        const val MAX_TEXT_LENGTH = 200
        const val MAX_NAME_LENGTH = 40

        /** Flattens the preview to one line and bounds its length. */
        fun previewOf(body: String): String = body.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_LENGTH)

        /** Bounds a display name carried inside a reply. */
        fun nameOf(name: String): String = name.trim().take(MAX_NAME_LENGTH)
    }
}
