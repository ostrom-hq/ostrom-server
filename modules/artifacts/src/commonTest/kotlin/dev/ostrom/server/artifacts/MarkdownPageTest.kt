// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts

import dev.ostrom.server.artifacts.presentation.MarkdownPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownPageTest {
    @Test
    fun blocksAreRendered() {
        val html = MarkdownPage.render(
            """
            # Title
            ### Sub *part*

            A paragraph
            on two lines.
            > quoted
            > again
            - one
            * two
            1. first
            2) second
            ```kotlin
            val a = 1 < 2
            ```
            ```
            unterminated
            """.trimIndent().replace("\n", "\r\n"),
        )
        assertTrue(html.startsWith("<!doctype html>"))
        listOf(
            "<h1>Title</h1>",
            "<h3>Sub <em>part</em></h3>",
            "<p>A paragraph on two lines.</p>",
            "<blockquote>quoted again</blockquote>",
            "<ul>\n<li>one</li>\n<li>two</li>\n</ul>",
            "<ol>\n<li>first</li>\n<li>second</li>\n</ol>",
            "<pre><code>val a = 1 &lt; 2</code></pre>",
            "<pre><code>unterminated</code></pre>",
        ).forEach { assertTrue(it in html, it) }
    }

    @Test
    fun aParagraphStopsAtTheNextBlock() {
        val html = MarkdownPage.render("text\n# Heading\ntext\n- item\ntext\n> q\ntext\n```\nc\n```\ntext\n1. n")
        assertEquals(5, Regex("<p>text</p>").findAll(html).count())
    }

    @Test
    fun inlineMarksAreEscaped() {
        assertEquals(
            "a <code>&lt;b&gt;</code> <strong>c</strong> <em>d</em> &amp; &quot;e&quot; &#39;f&#39;",
            MarkdownPage.inline("a `<b>` **c** *d* & \"e\" 'f'"),
        )
        assertEquals("<script>".let { MarkdownPage.escape(it) }, "&lt;script&gt;")
    }

    @Test
    fun onlySafeLinksAreKept() {
        assertEquals("<a href=\"https://x.io/a\">x</a>", MarkdownPage.inline("[x](https://x.io/a)"))
        assertEquals("<a href=\"img/a:b.png\">r</a>", MarkdownPage.inline("[r](img/a:b.png)"))
        assertEquals("<a href=\"page.html\">p</a>", MarkdownPage.inline("[p](page.html)"))
        assertEquals("<a href=\"MAILTO:a@b.c\">m</a>", MarkdownPage.inline("[m](MAILTO:a@b.c)"))
        assertEquals("js", MarkdownPage.inline("[js](javascript:alert)"))
        assertFalse(MarkdownPage.safeTarget("data:text/html,x"))
        assertFalse("<script" in MarkdownPage.render("<script>alert(1)</script>"))
    }
}
