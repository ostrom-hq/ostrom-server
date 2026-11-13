// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.services

/**
 * One line of a YAML file read as an outline: a key and its value, or a list item, with the lines indented under it.
 *
 * @property line Its line number, from 1.
 * @property indent The column of its key, or of its dash for a list item.
 * @property item True for a list item (`- …`).
 * @property key Its key, or null for a list item that holds only a value.
 * @property value Its value on the same line, without quotes or comment; null when it has none.
 * @property block The lines of its block scalar (`|`, `>`), with their numbers; empty otherwise.
 */
class YamlEntry(
    val line: Int,
    val indent: Int,
    val item: Boolean,
    val key: String?,
    val value: String?,
    val block: List<Pair<Int, String>>,
) {
    /** The entries indented under it. */
    val children: MutableList<YamlEntry> = mutableListOf()

    // Whether an entry at this indentation ends this one: a list may sit at its key's own indentation (`steps:` then
    // `- run: x` under the same column).
    internal fun closedBy(indent: Int, item: Boolean): Boolean =
        this.indent > indent || this.indent == indent && (!item || this.item)

    /**
     * The first child with this key, not a list item.
     *
     * @param name The key.
     * @return The child, or null.
     */
    fun child(name: String): YamlEntry? = children.firstOrNull { !it.item && it.key == name }

    /**
     * The fields of a mapping this entry opens: for a list item `- a: 1` followed by `b: 2`, both; for a key, its
     * children.
     *
     * @return The fields.
     */
    fun fields(): List<YamlEntry> = if (item && key != null) listOf(this) + children else children

    /**
     * Its scalar values: its value as a flow list (`[a, b]`) or a scalar, its list items' values, its block's lines.
     *
     * @return The values, in order.
     */
    fun scalars(): List<Pair<Int, String>> {
        val own = value?.let { v -> YamlOutline.flow(v).map { line to it } }.orEmpty()
        val items = children.filter { it.item && it.key == null }.flatMap { item ->
            item.value?.let { listOf(item.line to it) }.orEmpty() + item.block
        }
        return own + block + items
    }
}

/**
 * A small, bounded reader of the YAML the analysis needs (CI files, a skill's front matter): keys, values, list items
 * and block scalars by indentation, never anchors, tags or documents. It is iterative, so a hostile file of ten
 * thousand nested lines cannot exhaust a stack, and reads every line once.
 */
object YamlOutline {
    private val separators = setOf("---", "...")
    private val blockMarker = Regex("^[|>][-+0-9]*$")

    /**
     * Reads a text as an outline.
     *
     * @param text The YAML.
     * @return Its top-level entries.
     */
    fun parse(text: String): List<YamlEntry> {
        val root = YamlEntry(0, -1, false, null, null, emptyList())
        val stack = ArrayDeque(listOf(root))
        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            val number = ++index
            val entry = entry(lines, number) ?: continue
            entry.block.lastOrNull()?.let { index = it.first }
            while (stack.last().closedBy(entry.indent, entry.item)) stack.removeLast()
            stack.last().children += entry
            stack += entry
        }
        return root.children
    }

    // The entry of a line (numbered from 1) with its block, or null for a blank line, a comment or a separator.
    private fun entry(lines: List<String>, number: Int): YamlEntry? {
        val raw = lines[number - 1]
        val content = raw.trimStart()
        if (content.isEmpty() || content.startsWith("#") || content in separators) return null
        val indent = raw.length - content.length
        val item = content == "-" || content.startsWith("- ")
        val (key, value) = keyValue(if (item) content.drop(2).trimStart() else content)
        val marker = value != null && blockMarker.matches(value)
        // A block scalar's lines are indented past its key (`- run: |`: past the dash and its space), or past the dash
        // of an item that is only the block (`- |`).
        val column = if (item && key != null) indent + 2 else indent
        val block = if (marker) blockLines(lines, number, column) else emptyList()
        return YamlEntry(number, indent, item, key, if (marker) null else value, block)
    }

    // The lines of a block scalar from `start`: blank, or indented past `column`.
    private fun blockLines(lines: List<String>, start: Int, column: Int): List<Pair<Int, String>> {
        val taken = mutableListOf<Pair<Int, String>>()
        var index = start
        while (index < lines.size && (lines[index].isBlank() || indentOf(lines[index]) > column)) {
            if (lines[index].isNotBlank()) taken += (index + 1) to lines[index].trim()
            index++
        }
        return taken
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    // `key: value`, `key:`, or a plain value; a quoted key keeps its words.
    private fun keyValue(text: String): Pair<String?, String?> {
        val quoted = text.firstOrNull()?.takeIf { it == '"' || it == '\'' }
        val end = if (quoted != null) text.indexOf(quoted, 1) else -1
        val colon = when {
            quoted != null -> (end + 1).takeIf { end > 0 && text.getOrNull(it) == ':' }
            else -> keyColon(text)
        } ?: return null to scalar(text)
        val key = text.take(colon).trim().removeSurrounding("\"").removeSurrounding("'")
        val value = scalar(text.substring(colon + 1))
        return key to value
    }

    // The colon that ends a key: followed by a space or the end of the line.
    private fun keyColon(text: String): Int? {
        val spaced = text.indexOf(": ")
        return when {
            spaced >= 0 -> spaced
            text.endsWith(":") -> text.length - 1
            else -> null
        }
    }

    private fun scalar(text: String): String? {
        val trimmed = text.trim()
        val bare = if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            trimmed
        } else {
            trimmed.substringBefore(" #").trim()
        }
        return bare.removeSurrounding("\"").removeSurrounding("'").ifEmpty { null }
    }

    /**
     * The values of a flow list (`[push, pull_request]`), or the value itself.
     *
     * @param value The value.
     * @return The values.
     */
    fun flow(value: String): List<String> = if (value.startsWith("[") && value.endsWith("]")) {
        value.removeSurrounding("[", "]").split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            .filter { it.isNotEmpty() }
    } else {
        listOf(value)
    }
}
