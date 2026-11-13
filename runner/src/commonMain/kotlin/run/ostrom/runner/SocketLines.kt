// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readByte
import kotlinx.io.Buffer
import kotlinx.io.readString

private const val NEWLINE: Byte = 10

/**
 * Reads one line of a runner socket's request, without its newline.
 *
 * @param input The connection.
 * @param max The largest line, in bytes.
 *
 * @return The line, or null at once at the end of the stream.
 *
 * @throws IllegalArgumentException When the line is over [max] bytes.
 */
suspend fun readSocketLine(input: ByteReadChannel, max: Int): String? {
    val line = Buffer()
    while (!input.exhausted()) {
        val byte = input.readByte()
        if (byte == NEWLINE) return line.readString()
        require(line.size < max) { "the request is over $max bytes." }
        line.writeByte(byte)
    }
    return if (line.size == 0L) null else line.readString()
}
