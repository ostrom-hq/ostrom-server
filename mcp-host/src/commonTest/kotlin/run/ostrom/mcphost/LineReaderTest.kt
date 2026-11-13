// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LineReaderTest {
    @Test
    fun linesAreSplitAcrossChunks() {
        val reader = LineReader(1_024)
        assertEquals(emptyList(), reader.feed("ab".encodeToByteArray()))
        val lines = reader.feed("c\nd\n".encodeToByteArray() + ByteArray(10_000) { 'e'.code.toByte() }.copyOf(900))
        assertEquals(listOf("abc", "d"), lines.map { (it as LineReader.Line.Whole).bytes.decodeToString() })
        val tail = reader.feed("ef\n".encodeToByteArray())
        assertEquals(902, (tail.single() as LineReader.Line.Whole).bytes.size)
    }

    @Test
    fun anOversizedLineKeepsOnlyItsEdges() {
        val reader = LineReader(1_024)
        val start = "{\"id\":7," + "x".repeat(2_000)
        assertEquals(emptyList(), reader.feed(start.encodeToByteArray()))
        assertEquals(emptyList(), reader.feed("y".repeat(500).encodeToByteArray()))
        val lines = reader.feed(("z".repeat(10) + "\"id\":8}\nnext\n").encodeToByteArray())
        val oversize = assertIs<LineReader.Line.Oversize>(lines.first())
        assertTrue(oversize.head.decodeToString().startsWith("{\"id\":7,"))
        assertTrue(oversize.tail.decodeToString().endsWith("\"id\":8}"))
        assertEquals(128, oversize.tail.size)
        assertEquals("next", (lines[1] as LineReader.Line.Whole).bytes.decodeToString())
        val oneChunk = LineReader(1_024).feed(("q".repeat(3_000) + "\n").encodeToByteArray())
        assertIs<LineReader.Line.Oversize>(oneChunk.single())
    }

    @Test
    fun aLongLineWithinTheLimitGrowsTheBuffer() {
        val reader = LineReader(100_000)
        val line = "w".repeat(20_000)
        val lines = reader.feed((line + "\n").encodeToByteArray())
        assertEquals(line, (lines.single() as LineReader.Line.Whole).bytes.decodeToString())
    }
}
