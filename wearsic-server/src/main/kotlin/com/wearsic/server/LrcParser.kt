package com.wearsic.server

/**
 * LRC (LYRICS) parser — the common synced-lyrics format.
 *
 * Accepts one or more `[mm:ss.xx]` timestamps per line (overlaying tags),
 * both `.` and `:` millisecond separators, and 1/2/3-digit fraction widths.
 * Metadata tags (`[ti:...]`, `[ar:...]`, …) and untagged lines are ignored.
 * Lines are emitted sorted by time.
 */
object LrcParser {

    data class Parsed(val lines: List<LyricsLineDto>, val complete: Boolean)

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(raw: String): Parsed {
        if (raw.isBlank()) return Parsed(emptyList(), false)
        val out = ArrayList<LyricsLineDto>(64)
        var sawTag = false
        for (line in raw.lineSequence()) {
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) continue
            sawTag = true
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: continue
                val seconds = m.groupValues[2].toLongOrNull() ?: continue
                val fraction = m.groupValues[3]
                val fracMs = when {
                    fraction.isEmpty() -> 0L
                    else -> {
                        val shift = when (fraction.length) {
                            1 -> 100L
                            2 -> 10L
                            else -> 1L
                        }
                        (fraction.toLongOrNull() ?: 0L) * shift
                    }
                }
                val timeMs = minutes * 60_000L + seconds * 1_000L + fracMs
                out.add(LyricsLineDto(timeMs = timeMs, text = text))
            }
        }
        if (!sawTag) return Parsed(emptyList(), false)
        out.sortBy { it.timeMs }
        return Parsed(out, out.isNotEmpty())
    }
}
