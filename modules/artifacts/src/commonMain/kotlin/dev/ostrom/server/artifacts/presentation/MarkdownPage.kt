// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.presentation

/**
 * Renders a Markdown page into escaped HTML on the server: headings, paragraphs, lists, quotes, fenced code,
 * inline code, emphasis, and links to `http`, `https`, `mailto` or relative targets. Raw HTML in the Markdown is
 * shown as text, never interpreted.
 */
internal object MarkdownPage {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val INLINE = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val SAFE_SCHEMES = setOf("http", "https", "mailto")

    /**
     * Renders a page.
     *
     * @param markdown The page's text.
     *
     * @return A complete HTML document.
     */
    fun render(markdown: String): String {
        val body = StringBuilder()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var index = 0
        while (index < lines.size) {
            index = block(lines, index, body)
        }
        return SKELETON_START + body + SKELETON_END
    }

    /** Renders the block starting at [start] and answers the index after it. */
    private fun block(lines: List<String>, start: Int, out: StringBuilder): Int {
        val line = lines[start]
        val heading = HEADING.matchEntire(line)
        return when {
            line.isBlank() -> start + 1
            line.trimStart().startsWith("```") -> code(lines, start, out)
            heading != null -> {
                val level = heading.groupValues[1].length
                out.append("<h$level>").append(inline(heading.groupValues[2])).append("</h$level>\n")
                start + 1
            }
            line.startsWith(">") -> run(lines, start, out, quote = true)
            BULLET.matches(line) -> list(lines, start, out, "ul", BULLET)
            NUMBERED.matches(line) -> list(lines, start, out, "ol", NUMBERED)
            else -> run(lines, start, out, quote = false)
        }
    }

    private fun startsBlock(line: String): Boolean = HEADING.matches(line) || line.startsWith(">") ||
        line.trimStart().startsWith("```") || BULLET.matches(line) || NUMBERED.matches(line)

    private fun code(lines: List<String>, start: Int, out: StringBuilder): Int {
        var end = start + 1
        while (end < lines.size && !lines[end].trimStart().startsWith("```")) end++
        out.append("<pre><code>").append(escape(lines.subList(start + 1, end).joinToString("\n")))
            .append("</code></pre>\n")
        return minOf(end + 1, lines.size)
    }

    /** A quote (lines starting with `>`) or a paragraph (lines up to a blank line or another block). */
    private fun run(lines: List<String>, start: Int, out: StringBuilder, quote: Boolean): Int {
        val continues: (String) -> Boolean =
            if (quote) { line -> line.startsWith(">") } else { line -> line.isNotBlank() && !startsBlock(line) }
        var end = start + 1
        while (end < lines.size && continues(lines[end])) end++
        val joined = lines.subList(start, end).joinToString(" ") { it.removePrefix(if (quote) ">" else "").trim() }
        val tag = if (quote) "blockquote" else "p"
        out.append("<$tag>").append(inline(joined)).append("</$tag>\n")
        return end
    }

    private fun list(lines: List<String>, start: Int, out: StringBuilder, tag: String, item: Regex): Int {
        var end = start
        out.append("<$tag>\n")
        while (end < lines.size) {
            val match = item.matchEntire(lines[end]) ?: break
            out.append("<li>").append(inline(match.groupValues[1])).append("</li>\n")
            end++
        }
        out.append("</$tag>\n")
        return end
    }

    /** Escapes text and renders its inline marks. */
    internal fun inline(text: String): String {
        val out = StringBuilder()
        var last = 0
        INLINE.findAll(text).forEach { match ->
            out.append(escape(text.substring(last, match.range.first)))
            val (code, bold, em, label, target) = match.destructured
            out.append(
                when {
                    code.isNotEmpty() -> "<code>${escape(code)}</code>"
                    bold.isNotEmpty() -> "<strong>${escape(bold)}</strong>"
                    em.isNotEmpty() -> "<em>${escape(em)}</em>"
                    safeTarget(target) -> "<a href=\"${escape(target)}\">${escape(label)}</a>"
                    else -> escape(label)
                },
            )
            last = match.range.last + 1
        }
        return out.append(escape(text.substring(last))).toString()
    }

    /** A link target without a scheme (relative) or with a safe one; never `javascript:` or `data:`. */
    internal fun safeTarget(target: String): Boolean {
        val colon = target.indexOf(':')
        val slash = target.indexOf('/').let { if (it < 0) target.length else it }
        return colon < 0 || colon > slash || target.substring(0, colon).lowercase() in SAFE_SCHEMES
    }

    /** Escapes HTML's special characters. */
    internal fun escape(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private const val SKELETON_START = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{font:16px/1.6 system-ui,sans-serif;max-width:72ch;margin:2rem auto;padding:0 16px;color-scheme:light dark}
pre{overflow:auto;padding:12px;background:rgba(127,127,127,.12);border-radius:6px}
code{font-family:ui-monospace,monospace}blockquote{margin:0;padding-left:1rem;border-left:3px solid #8888}</style>
</head><body>
"""
    private const val SKELETON_END = "</body></html>\n"
}
