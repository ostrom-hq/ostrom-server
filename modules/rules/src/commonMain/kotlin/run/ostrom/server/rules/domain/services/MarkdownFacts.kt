// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

/**
 * One item of a list, as written on its first line.
 *
 * @property line Its line.
 * @property text Its text, without the list marker; untrusted, shown only through [RepositoryContext.shown].
 */
data class ListItem(val line: Int, val text: String)

/**
 * A heading followed by a numbered list: steps the team wrote down.
 *
 * @property heading The heading's text.
 * @property line The heading's line.
 * @property items The list's items, at most [MarkdownFacts.MAX_ITEMS].
 */
data class InstructionList(val heading: String, val line: Int, val items: List<ListItem>)

/**
 * A skill's or a command's front matter (`name`, `description`).
 *
 * @property name Its `name`, if written.
 * @property description Its `description`, if written, a folded one joined on one line.
 * @property line The line of the description, or 1.
 */
data class FrontMatter(val name: String?, val description: String?, val line: Int)

/**
 * Reads the Markdown files a process comes from (agent instructions, `CONTRIBUTING.md`, a pull request template, a
 * skill): headings and the numbered list under each, checklist items, front matter. Fenced code and HTML comments are
 * skipped; only the first line of an item is kept.
 */
object MarkdownFacts {
    /** The items one list gives at most. */
    const val MAX_ITEMS: Int = 50

    /** The lists one file gives at most. */
    const val MAX_LISTS: Int = 10

    /** The front matter lines read at most. */
    const val MAX_FRONT_MATTER: Int = 50

    private val heading = Regex("^ {0,3}#{1,6}\\s+(.*?)[\\s#]*$")
    private val numbered = Regex("^ {0,3}\\d{1,9}[.)]\\s+(.*)$")
    private val checked = Regex("^\\s*[-*+]\\s+\\[[ xX]]\\s+(.*)$")
    private val fence = Regex("^ {0,3}(```|~~~)")
    private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val code = Regex("`([^`]+)`")

    /**
     * The headings each followed by a numbered list of at least two items: text between the heading and the list is
     * allowed, and the list ends at the next line that is neither an item, indented, nor blank.
     *
     * @param text The file.
     * @return The lists, in file order, at most [MAX_LISTS].
     */
    fun lists(text: String): List<InstructionList> {
        val found = mutableListOf<InstructionList>()
        var open: InstructionList? = null
        var listing = false
        for ((number, line) in prose(text)) {
            val title = heading.find(line)?.groupValues?.get(1)
            val item = numbered.find(line)?.groupValues?.get(1)
            val current = open
            when {
                title != null -> {
                    current?.let { found += it }
                    open = InstructionList(title, number, emptyList())
                    listing = false
                }
                current == null -> Unit
                item != null -> {
                    open = current.copy(items = (current.items + ListItem(number, item)).take(MAX_ITEMS))
                    listing = true
                }
                // An item's continuation, a nested list or a blank line keeps the list open; anything else ends it.
                listing && (line.isBlank() || line.startsWith(" ") || line.startsWith("\t")) -> Unit
                listing -> {
                    found += current
                    open = null
                }
            }
        }
        open?.let { found += it }
        return found.filter { it.items.size >= 2 }.take(MAX_LISTS)
    }

    /**
     * The checklist items (`- [ ] …`) of a file, such as a pull request template.
     *
     * @param text The file.
     * @return The items, in file order, at most [MAX_ITEMS].
     */
    fun checklist(text: String): List<ListItem> = prose(text)
        .mapNotNull { (number, line) -> checked.find(line)?.let { ListItem(number, it.groupValues[1]) } }
        .take(MAX_ITEMS)
        .toList()

    /**
     * The front matter of a skill or a command: the lines between a first `---` and the next.
     *
     * @param text The file.
     * @return Its `name` and `description`; both null without front matter.
     */
    fun frontMatter(text: String): FrontMatter {
        val lines = text.lines().take(MAX_FRONT_MATTER)
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (lines.first().trim() != "---" || end < 0) return FrontMatter(null, null, 1)
        val entries = YamlOutline.parse(lines.subList(1, end + 1).joinToString("\n"))
        fun field(key: String) = entries.firstOrNull { !it.item && it.key == key }
        val description = field("description")
        val folded = description?.let { entry ->
            (listOfNotNull(entry.value) + entry.block.map { it.second }).joinToString(" ").ifEmpty { null }
        }
        return FrontMatter(field("name")?.value, folded, (description?.line ?: 0) + 1)
    }

    /**
     * An item's or a heading's words as Markdown shows them: a link is its text, emphasis marks go.
     *
     * @param text The text.
     * @return The words, untrusted still.
     */
    fun words(text: String): String = link.replace(text, "$1").replace("**", "").replace("__", "").trim()

    /**
     * The spans in backticks of a text, the commands it names.
     *
     * @param text The text.
     * @return The spans, trimmed.
     */
    fun codeSpans(text: String): List<String> = code.findAll(text).map { it.groupValues[1].trim() }.toList()

    // The numbered lines outside fenced code and HTML comments.
    private fun prose(text: String): Sequence<Pair<Int, String>> = sequence {
        var fenced = false
        var comment = false
        for ((index, line) in text.lines().withIndex()) {
            when {
                fence.containsMatchIn(line) -> fenced = !fenced
                fenced -> Unit
                comment -> comment = "-->" !in line
                "<!--" in line -> comment = "-->" !in line.substringAfter("<!--")
                else -> yield(index + 1 to line)
            }
        }
    }
}
