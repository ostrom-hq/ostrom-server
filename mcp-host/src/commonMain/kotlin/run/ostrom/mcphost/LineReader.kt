// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

/**
 * Splits a process's stdout into lines, one JSON-RPC message each. A line longer than the limit is never kept whole:
 * only its first and last [EDGE_BYTES] bytes, where an answer carries its id, so its call can fail at once.
 *
 * @param maxLineBytes The longest line kept.
 */
class LineReader(private val maxLineBytes: Int) {
    /** One line of output. */
    sealed interface Line {
        /**
         * A line within the limit, without its newline.
         *
         * @property bytes Its bytes.
         */
        class Whole(val bytes: ByteArray) : Line

        /**
         * A line over the limit, dropped unparsed.
         *
         * @property head Its first bytes.
         * @property tail Its last bytes.
         */
        class Oversize(val head: ByteArray, val tail: ByteArray) : Line
    }

    private var line = ByteArray(INITIAL)
    private var size = 0
    private var head: ByteArray? = null
    private var tail = ByteArray(0)

    /**
     * Reads a chunk.
     *
     * @param chunk The bytes read.
     * @return The lines it completed.
     */
    fun feed(chunk: ByteArray): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        for (index in chunk.indices) {
            if (chunk[index] != NEWLINE) continue
            take(chunk, start, index)
            lines += head?.let { Line.Oversize(it, tail) } ?: Line.Whole(line.copyOf(size))
            size = 0
            head = null
            tail = ByteArray(0)
            start = index + 1
        }
        take(chunk, start, chunk.size)
        return lines
    }

    private fun take(chunk: ByteArray, from: Int, until: Int) {
        if (head != null) {
            keepTail(chunk, from, until)
            return
        }
        append(chunk, from, until)
        // Over the limit: the rest of the line is skipped without being kept or parsed.
        if (size > maxLineBytes) {
            head = line.copyOf(EDGE_BYTES)
            tail = line.copyOfRange(size - EDGE_BYTES, size)
            size = 0
        }
    }

    private fun append(chunk: ByteArray, from: Int, until: Int) {
        val count = until - from
        if (size + count > line.size) line = line.copyOf(maxOf(line.size * 2, size + count))
        chunk.copyInto(line, size, from, until)
        size += count
    }

    private fun keepTail(chunk: ByteArray, from: Int, until: Int) {
        val joined = tail + chunk.copyOfRange(maxOf(from, until - EDGE_BYTES), until)
        tail = joined.copyOfRange(maxOf(0, joined.size - EDGE_BYTES), joined.size)
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val EDGE_BYTES = 128
        const val INITIAL = 8 * 1024
    }
}
