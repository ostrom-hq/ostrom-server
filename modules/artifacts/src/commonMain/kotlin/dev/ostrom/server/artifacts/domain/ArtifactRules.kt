// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.domain

/** What a publication may hold, checked before anything is stored (TeamCode's artifact rules, simplified). */
object ArtifactRules {
    /** Files per version. */
    const val MAX_FILES = 200

    /** One file. */
    const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /** All the files of a version. */
    const val MAX_VERSION_BYTES = 20L * 1024 * 1024

    /** A title, in characters. */
    const val MAX_TITLE_LENGTH = 200

    /** A path, in characters. */
    const val MAX_PATH_LENGTH = 200

    private val SEGMENT = Regex("[A-Za-z0-9._-]+")
    private const val DEFAULT_TYPE = "application/octet-stream"
    private val TYPES = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "md" to "text/markdown; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json",
        "csv" to "text/csv; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "pdf" to "application/pdf",
        "wasm" to "application/wasm",
    )
    private val PAGE_EXTENSIONS = listOf(".html", ".htm", ".md")

    /** Credentials a page must never carry; the same families as TeamCode's transcript mask. */
    private val SECRETS = listOf(
        Regex("sk-ant-[A-Za-z0-9_-]{20,}"),
        Regex("sk-(?:proj-|or-v1-)?[A-Za-z0-9_-]{32,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{36,}"),
        Regex("github_pat_[A-Za-z0-9_]{40,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("ostrom_[a-z]+_[A-Za-z0-9]{32,}"),
        Regex("AIza[0-9A-Za-z_-]{35}"),
        Regex("[rs]k_live_[0-9a-zA-Z]{24,}"),
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
    )

    /**
     * Checks a title.
     *
     * @param title The title as sent.
     *
     * @return The title, trimmed.
     *
     * @throws InvalidArtifactRequestException When it is empty, too long or holds control characters.
     */
    fun title(title: String): String {
        val trimmed = title.trim()
        ensureValid(trimmed.isNotEmpty() && trimmed.length <= MAX_TITLE_LENGTH && trimmed.none { it.isISOControl() }) {
            "The title must be 1 to $MAX_TITLE_LENGTH characters, without control characters."
        }
        return trimmed
    }

    /**
     * Checks a version's files: count, paths, sizes and secrets.
     *
     * @param files The files.
     *
     * @throws InvalidArtifactRequestException When a path is invalid or repeated, or there is no file.
     * @throws ArtifactTooLargeException When a file or the version is too large, or has too many files.
     * @throws ArtifactSecretException When a text file holds a credential.
     */
    fun check(files: List<UploadedFile>) {
        ensureValid(files.isNotEmpty()) { "A version needs at least one file." }
        checkSizes(files)
        files.forEach { checkPath(it.path) }
        ensureValid(files.map { it.path }.toSet().size == files.size) { "Two files have the same path." }
        files.firstOrNull { holdsSecret(it.bytes) }?.let { throw ArtifactSecretException(it.path) }
    }

    private fun checkSizes(files: List<UploadedFile>) {
        if (files.size > MAX_FILES) throw ArtifactTooLargeException("A version holds at most $MAX_FILES files.")
        val oversized = files.firstOrNull { it.bytes.size > MAX_FILE_BYTES }
        if (oversized != null || files.sumOf { it.bytes.size.toLong() } > MAX_VERSION_BYTES) {
            throw ArtifactTooLargeException(
                "A file holds at most ${MAX_FILE_BYTES / MIB} MiB and a version ${MAX_VERSION_BYTES / MIB} MiB.",
            )
        }
    }

    /**
     * Checks a path: relative, `/`-separated segments of letters, digits, `.`, `_` and `-`, none `.` or `..`.
     *
     * @param path The path.
     *
     * @throws InvalidArtifactRequestException When it is not such a path.
     */
    fun checkPath(path: String) {
        val segments = path.split('/')
        ensureValid(
            path.length <= MAX_PATH_LENGTH &&
                segments.all { SEGMENT.matches(it) && it != "." && it != ".." },
        ) { "${path.take(MAX_PATH_LENGTH)} is not a valid file path (relative, letters, digits, . _ - and /)." }
    }

    /**
     * The page a reader opens first: `index.html`, else the first HTML page, else the first Markdown page, else the
     * first file.
     *
     * @param paths The version's paths.
     *
     * @return The entry page.
     */
    fun entry(paths: List<String>): String {
        val sorted = paths.sorted()
        return sorted.firstOrNull { it == "index.html" }
            ?: PAGE_EXTENSIONS.firstNotNullOfOrNull { extension -> sorted.firstOrNull { it.endsWith(extension) } }
            ?: sorted.first()
    }

    /**
     * The type a file is served with, from its extension.
     *
     * @param path The file.
     *
     * @return The type; `application/octet-stream` for an unknown extension.
     */
    fun contentType(path: String): String =
        TYPES[path.substringAfterLast('/').substringAfterLast('.', "").lowercase()] ?: DEFAULT_TYPE

    /**
     * Whether a file is text (UTF-8 without a NUL byte) holding a credential.
     *
     * @param bytes The file.
     *
     * @return True when a credential was found.
     */
    fun holdsSecret(bytes: ByteArray): Boolean {
        if (bytes.any { it == NUL }) return false
        val text = bytes.decodeToString()
        return SECRETS.any { it.containsMatchIn(text) }
    }

    private const val MIB = 1024 * 1024
    private const val NUL: Byte = 0
}
