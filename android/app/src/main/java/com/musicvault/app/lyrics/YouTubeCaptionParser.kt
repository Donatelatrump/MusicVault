package com.musicvault.app.lyrics

/** Converts the WebVTT returned by YouTube's timed-text endpoint into lyric lines. */
object YouTubeCaptionParser {
    private val cue = Regex("""(?m)^(?:(\d{2,}):)?(\d{2}):(\d{2})[.,](\d{3})\s+-->[^\n]*\n((?:(?!\n\s*\n)[\s\S])+)""")
    private val tags = Regex("<[^>]+>")
    private val nonLyrics = Regex(
        """^\s*[\[(](upbeat\s+)?(music|musica|música|applause|aplausos|instrumental)[\])]\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): Lyrics? {
        val lines = cue.findAll(raw.replace("\r\n", "\n").replace('\r', '\n')).mapNotNull { match ->
            val text = match.groupValues[5]
                .lineSequence()
                .joinToString(" ") { it.replace(tags, "").trim() }
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("♪", "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isBlank() || nonLyrics.matches(text)) return@mapNotNull null
            val timeMs = (match.groupValues[1].toLongOrNull() ?: 0L) * 3_600_000L +
                match.groupValues[2].toLong() * 60_000L +
                match.groupValues[3].toLong() * 1_000L + match.groupValues[4].toLong()
            LyricLine(timeMs, text)
        }.distinctBy { it.timeMs to it.text }.toList()
        if (lines.size < 5) return null
        return Lyrics(plain = lines.joinToString("\n") { it.text }, synced = lines)
    }
}
