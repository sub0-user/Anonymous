package org.server.anonymous.controller

import javafx.scene.Node
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.text.Text
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/**
 * Bundled color emoji palette — Twemoji PNGs baked into the app at build time (JavaFX cannot
 * rasterize color emoji fonts, so the picker and message bubbles render them as images).
 */
object EmojiImages {
    sealed interface Segment {
        data class Text(
            val value: String,
        ) : Segment

        data class Emoji(
            val value: String,
        ) : Segment
    }

    private val bundle = ResourceBundle.getBundle("org.server.anonymous.emojis")

    val emojis: List<String> = bundle.getString("emoji.list").split(" ").filter { it.isNotBlank() }

    private val cache = ConcurrentHashMap<String, Image>()

    /** The color PNG for one emoji, or null when the asset is missing (fall back to text). */
    fun imageOf(emoji: String): Image? = cache[emoji] ?: load(emoji)

    /** The baked PNG asset for one emoji, or null when it is missing. */
    internal fun assetOf(emoji: String): java.net.URL? =
        EmojiImages::class.java.getResource("/org/server/anonymous/emoji/${fileName(emoji)}.png")

    private fun load(emoji: String): Image? {
        val url = assetOf(emoji) ?: return null
        val image = url.openStream().use { Image(it) }
        cache[emoji] = image
        return image
    }

    /** "😀" -> "1f600"; "❤️" -> "2764-fe0f" — matches the baked Twemoji asset names. */
    private fun fileName(emoji: String): String =
        buildString {
            for (cp in emoji.codePoints().toArray()) {
                if (isNotEmpty()) append('-')
                append("%04x".format(cp))
            }
        }

    /** Splits [text] into plain-text and emoji runs (longest match against the palette). */
    fun split(text: String): List<Segment> {
        val result = mutableListOf<Segment>()
        val textRun = StringBuilder()

        fun flushText() {
            if (textRun.isNotEmpty()) {
                result += Segment.Text(textRun.toString())
                textRun.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            var matched: String? = null
            for (emoji in emojis) {
                if (text.startsWith(emoji, i) && (matched == null || emoji.length > matched.length)) {
                    matched = emoji
                }
            }
            if (matched != null) {
                flushText()
                result += Segment.Emoji(matched)
                i += matched.length
            } else {
                textRun.append(text[i])
                i++
            }
        }
        flushText()
        return result
    }

    /** Bubble content: text runs as styled Text nodes, emoji runs as color images. */
    fun nodesFor(text: String): List<Node> =
        split(text).map { segment ->
            when (segment) {
                is Segment.Text -> Text(segment.value).apply { styleClass.add("bubble-text") }
                is Segment.Emoji -> {
                    val image = imageOf(segment.value)
                    if (image != null) {
                        ImageView(image).apply {
                            fitHeight = 17.0
                            fitWidth = 17.0
                            setPreserveRatio(true)
                        }
                    } else {
                        Text(segment.value).apply { styleClass.add("bubble-text") }
                    }
                }
            }
        }
}
