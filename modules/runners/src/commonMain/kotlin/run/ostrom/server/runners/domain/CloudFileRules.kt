// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.kernel.errors.ErrorKind
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * A refusal of an upload's quota: `429 upload_quota`, with the seconds to wait for a daily one (`Retry-After`).
 *
 * @property retryAfterSeconds Seconds until the member's UTC day ends; null for a session's own bound.
 */
class CloudFileQuotaException(message: String, val retryAfterSeconds: Long? = null) :
    CloudConversationException(ErrorKind.RATE_LIMITED, "upload_quota", message)

/**
 * The limits and checks of files sent to a session (`runners.cloud.files.v1`, TeamCode's `CloudFileRules`): what a
 * member may upload, how much, and what the host may name the file on its disk. The server decides the type from the
 * bytes, never from what the client says.
 */
@Suppress("MagicNumber", "TooManyFunctions") // The file signatures, byte by byte; TeamCode's one object of rules.
object CloudFileRules {
    /** One mebibyte. */
    const val MEBIBYTE: Long = 1024L * 1024

    /** The defaults of the runtime's settings, and the most it may set. */
    const val DEFAULT_FILE_MEBIBYTES: Int = CloudRuntime.DEFAULT_FILE_MEBIBYTES

    /** Files of one message together, by default. */
    const val DEFAULT_MESSAGE_MEBIBYTES: Int = CloudRuntime.DEFAULT_MESSAGE_FILE_MEBIBYTES

    /** Files one message names at most. */
    const val MAX_FILES_PER_MESSAGE: Int = 5

    /** Bytes a session holds at most in files not yet received by its host (uploaded, sent or not, and not expired). */
    const val SESSION_BUFFER_BYTES: Long = 100 * MEBIBYTE

    /** Files a session holds at most that no message names yet: the upload counterpart of the pending-message bound. */
    const val MAX_UNSENT_FILES_PER_SESSION: Int = 20

    /** What one member may upload per UTC day, all sessions together. */
    const val MEMBER_DAILY_BYTES: Long = 1024 * MEBIBYTE

    /** Files one member may upload per UTC day. */
    const val MEMBER_DAILY_FILES: Int = 500

    /** A file is kept at most this long, sent or not, received by its host or not. */
    const val TTL_MILLIS: Long = 24 * 60 * 60_000L

    /** One UTC day. */
    const val DAY_MILLIS: Long = 24 * 60 * 60_000L

    /** Objects of 1 MiB in the bucket, as artifact upload chunks: a download streams them one by one. */
    const val PART_BYTES: Int = 1024 * 1024

    /** Longest name a client may send before it is cleaned, in characters. */
    const val MAX_RAW_NAME_CHARS: Int = 1_024

    /**
     * Longest cleaned name, in UTF-8 bytes: the host writes `<fileId>-<name>`, 37 + 120 bytes, well under the 255 bytes
     * file systems allow.
     */
    const val MAX_NAME_BYTES: Int = 120
    private const val MAX_EXTENSION_CHARS = 10

    /** The types a session accepts, by the type the server recognized. */
    val ALLOWED_TYPES: List<String> = listOf(
        "image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "text/plain", "text/markdown",
        "text/csv", "application/json",
    )

    /** Extensions a binary type keeps; any other gets the first one appended: the agent never reads a PNG as `.exe`. */
    private val BINARY_EXTENSIONS = mapOf(
        "image/png" to listOf("png"),
        "image/jpeg" to listOf("jpg", "jpeg"),
        "image/webp" to listOf("webp"),
        "image/gif" to listOf("gif"),
        "application/pdf" to listOf("pdf"),
    )
    private val BINARY_EXTENSION_SET = BINARY_EXTENSIONS.values.flatten().toSet()

    /** @return The largest file of the runtime, in bytes. */
    fun fileLimit(runtime: CloudRuntime): Long = runtime.fileMaxMebibytes * MEBIBYTE

    /** @return The largest total of one message's files, in bytes. */
    fun messageLimit(runtime: CloudRuntime): Long = runtime.messageFileMebibytes * MEBIBYTE

    /** @return The `413 file_too_large` of a file above [limit]. */
    fun tooLarge(limit: Long): CloudConversationException = CloudConversationException(
        ErrorKind.PAYLOAD_TOO_LARGE,
        "file_too_large",
        "A file is limited to ${limit / MEBIBYTE} MiB on this server.",
    )

    /** @return The `415 file_type_not_allowed` of a file that is neither an image, a PDF nor UTF-8 text. */
    fun typeNotAllowed(): CloudConversationException = CloudConversationException(
        ErrorKind.UNSUPPORTED_MEDIA_TYPE,
        "file_type_not_allowed",
        "Send an image (PNG, JPEG, WebP, GIF), a PDF, or UTF-8 text (plain, Markdown, CSV, JSON).",
    )

    /** @return The `429 upload_quota`, with the seconds to wait when it is the member's day. */
    fun quota(message: String, retryAfterSeconds: Long? = null): CloudFileQuotaException =
        CloudFileQuotaException(message, retryAfterSeconds)

    /** @return The binary type the first bytes announce, or null (text is told apart by [TextCheck]). */
    fun magicType(head: ByteArray): String? {
        fun starts(vararg bytes: Int, at: Int = 0) =
            head.size >= at + bytes.size && bytes.indices.all { head[at + it] == bytes[it].toByte() }
        return when {
            starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            starts(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || starts(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) -> "image/gif"
            starts(0x52, 0x49, 0x46, 0x46) && starts(0x57, 0x45, 0x42, 0x50, at = 8) -> "image/webp"
            starts(0x25, 0x50, 0x44, 0x46, 0x2D) -> "application/pdf"
            else -> null
        }
    }

    /** @return A text file's type, from its cleaned name: Markdown, CSV and JSON by extension, plain text otherwise. */
    fun textType(name: String): String = when (extension(name)) {
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "text/plain"
    }

    /**
     * The name the file gets once its type is known: a binary file keeps its extension only when it is one of its
     * type's, and text never keeps a binary type's extension; otherwise the type's extension is appended.
     *
     * @return The final name.
     */
    fun finalName(name: String, contentType: String): String {
        val extension = extension(name)
        val wanted = BINARY_EXTENSIONS[contentType]
        val suffix = when {
            wanted != null -> if (extension in wanted) null else wanted.first()
            extension in BINARY_EXTENSION_SET -> "txt"
            else -> null
        } ?: return name
        val base = fit(name, MAX_NAME_BYTES - suffix.length - 1)
        return "$base.$suffix"
    }

    /**
     * The client's file name, made safe to use as the last segment of a path on any host. It is never trusted as a
     * path: only the part after the last `/` or `\` is kept, normalized to NFC; control, format (bidirectional
     * overrides, zero-width), separator and surrogate characters are dropped; characters Windows refuses are replaced
     * by `_`; leading dots and dashes, trailing dots and spaces are removed; Windows device names (`CON`, `NUL`,
     * `COM1`…) are prefixed by `_`; the result is cut to [MAX_NAME_BYTES] UTF-8 bytes, keeping a short extension.
     * Empty becomes `file`. The host still prefixes it with the file id and checks the final path stays in the
     * session's directory.
     *
     * @return The cleaned name.
     * @throws CloudConversationException `invalid_file_name` for a name over [MAX_RAW_NAME_CHARS] characters.
     */
    fun sanitizeName(raw: String?): String {
        if (raw != null && raw.length > MAX_RAW_NAME_CHARS) {
            throw CloudConversationRules.invalid(
                "invalid_file_name",
                "Send a file name of at most $MAX_RAW_NAME_CHARS characters.",
            )
        }
        val last = raw.orEmpty().split('/', '\\').lastOrNull { it.isNotBlank() }.orEmpty()
        val normalized = Normalizer.normalize(last, Normalizer.Form.NFC)
        val cleaned = buildString {
            var index = 0
            while (index < normalized.length) {
                val code = normalized.codePointAt(index)
                index += Character.charCount(code)
                when {
                    Character.getType(code).toByte() in DROPPED_TYPES -> Unit
                    code < 0x80 && code.toChar() in WINDOWS_FORBIDDEN -> append('_')
                    Character.isWhitespace(code) || Character.isSpaceChar(code) -> append(' ')
                    else -> appendCodePoint(code)
                }
            }
        }.replace(Regex(" {2,}"), " ").trim(' ', '.').trimStart('-', '.', ' ').trimEnd('.', ' ')
        var name = cleaned.ifEmpty { "file" }
        val stem = name.substringBefore('.').trimEnd(' ').uppercase()
        if (stem in WINDOWS_DEVICES || WINDOWS_NUMBERED.matches(stem)) name = "_$name"
        return fit(name, MAX_NAME_BYTES)
    }

    /** Cuts [name] to [limit] UTF-8 bytes on a character boundary, keeping an extension of up to 10 characters. */
    private fun fit(name: String, limit: Int): String {
        if (name.encodeToByteArray().size <= limit) return name
        val extension = name.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_CHARS && it != name }
        val suffix = extension?.let { ".$it" }.orEmpty()
        val stem = if (extension != null) name.removeSuffix(suffix) else name
        val room = limit - suffix.encodeToByteArray().size
        var bytes = 0
        // Whole characters only, while they fit: the name is longer than the limit, so one never does.
        val kept = stem.codePoints().toArray().map { String(Character.toChars(it)) }
            .takeWhile { character ->
                bytes += character.encodeToByteArray().size
                bytes <= room
            }.joinToString("")
        return kept.trimEnd('.', ' ').ifEmpty { "file" } + suffix
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Control, format, line and paragraph separators, surrogates, private use and unassigned code points. */
    private val DROPPED_TYPES = setOf(
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
    )
    private val WINDOWS_FORBIDDEN = setOf('<', '>', ':', '"', '|', '?', '*')
    private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$")
    private val WINDOWS_NUMBERED = Regex("(COM|LPT)[0-9¹²³]")

    /**
     * Checks that a stream of bytes is UTF-8 text without NUL bytes, chunk by chunk: a character split between two
     * chunks is carried over. A byte-order mark is allowed.
     */
    class TextCheck {
        private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        private var carry = ByteArray(0)
        private val output = CharBuffer.allocate(OUTPUT_CHARS)

        /** False once a byte was not text. */
        var valid: Boolean = true
            private set

        /** Checks the next bytes. */
        fun feed(bytes: ByteArray) {
            if (!valid) return
            if (bytes.any { it == 0.toByte() }) {
                valid = false
                return
            }
            decode(carry + bytes, false)
        }

        /** @return Whether everything fed was text, with no character left incomplete. */
        fun finish(): Boolean {
            if (valid) decode(carry, true)
            return valid && carry.isEmpty()
        }

        private fun decode(bytes: ByteArray, end: Boolean) {
            val input = ByteBuffer.wrap(bytes)
            while (true) {
                output.clear()
                val result = decoder.decode(input, output, end)
                if (result.isError) {
                    valid = false
                    carry = ByteArray(0)
                    return
                }
                if (result.isUnderflow) break
            }
            carry = ByteArray(input.remaining()).also { input.get(it) }
        }

        private companion object {
            const val OUTPUT_CHARS = 8_192
        }
    }
}
