package com.linkguard.app.update

/**
 * One rendered line of release notes: either a section [isHeader] (bold) or a bullet point.
 * Pure data so the formatting can be unit-tested without inflating any views.
 */
data class NoteLine(val text: String, val isHeader: Boolean)

/**
 * Turns the raw GitHub release `body` (Markdown) into a short, clean list of lines for the
 * "Update available" card. Intentionally lightweight — not a full Markdown parser:
 *
 *  - `## Heading` and `**Heading**` lines become headers.
 *  - `- `, `* `, `• ` prefixed lines become bullets; other prose lines are kept as bullets too.
 *  - Inline emphasis (`**bold**`, `` `code` ``) and `[text](url)` links are reduced to plain text.
 *  - Horizontal rules and the trailing `SHA-256:` checksum footer are dropped (noise to a user).
 *
 * The list is capped so a runaway body can't make the dialog unbounded; the notes area scrolls.
 */
fun formatReleaseNotes(raw: String): List<NoteLine> {
    if (raw.isBlank()) return emptyList()

    val out = mutableListOf<NoteLine>()
    for (rawLine in raw.lines()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        // Drop the checksum footer we append to every release note.
        if (trimmed.startsWith("SHA-256", ignoreCase = true)) continue
        // Drop horizontal rules (---, ***, ___).
        if (trimmed.length >= 3 && trimmed.all { it == '-' || it == '*' || it == '_' }) continue

        val isBoldHeading = BOLD_HEADING.matches(trimmed)
        val isHeader = trimmed.startsWith("#") || isBoldHeading

        var line = trimmed.trimStart('#', ' ', '\t')
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
            line = line.substring(2).trim()
        }
        line = stripInlineMarkdown(line)
        if (line.isEmpty()) continue

        out.add(NoteLine(line, isHeader))
        if (out.size >= MAX_LINES) break
    }
    return out
}

private const val MAX_LINES = 15
private val BOLD_HEADING = Regex("^\\*\\*.+\\*\\*$")
private val MD_LINK = Regex("\\[(.+?)]\\((.+?)\\)")

private fun stripInlineMarkdown(s: String): String =
    s.replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(MD_LINK, "$1")
        .trim()
