// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.domain

/** SHA-256 of bytes, lower-case hex: the platform's cryptography provider in production. */
fun interface Sha256 {
    /**
     * Hashes bytes.
     *
     * @param bytes The bytes.
     *
     * @return Lower-case hex.
     */
    suspend fun hex(bytes: ByteArray): String
}

/**
 * The rules shared by skill and hook artifacts (TeamCode's `SkillArtifacts`): names, version labels, portable paths,
 * the file tree and the canonical digest. A path is relative, uses `/`, has at most [MAX_PATH_DEPTH] segments and
 * [MAX_PATH_LENGTH] characters, and each segment matches `[A-Za-z0-9_][A-Za-z0-9._+-]*`, does not end with a dot and is
 * not a Windows device name: no `..`, `.`, absolute path, drive letter, backslash, hidden file or space. Paths are
 * unique ignoring case and none is both a file and a directory, so an artifact installs alike on macOS, Linux and
 * Windows.
 */
object ArtifactTree {
    /** The most segments of a path. */
    const val MAX_PATH_DEPTH: Int = 8

    /** The longest path. */
    const val MAX_PATH_LENGTH: Int = 255

    private const val MAX_NAME_LENGTH = 64
    private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")
    private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9._+-]{0,127}")
    private val DIGEST = Regex("sha256:[0-9a-f]{64}")
    private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /**
     * Checks a catalog name: 1 to 64 lowercase letters, digits and single hyphens.
     *
     * @param kind `skill` or `hook`, which names the error key.
     *
     * @throws InvalidCatalogRequestException `invalid_<kind>_name` otherwise.
     */
    fun checkName(name: String, kind: String) = ensureValid(
        name.length <= MAX_NAME_LENGTH && NAME.matches(name),
        "invalid_${kind}_name",
    ) { "Use a $kind name of 1 to 64 lowercase letters, digits and single hyphens." }

    /**
     * Checks a version label: 1 to 64 characters `[0-9A-Za-z][0-9A-Za-z.+_-]*`.
     *
     * @param kind `skill` or `hook`.
     *
     * @throws InvalidCatalogRequestException `invalid_<kind>_version` otherwise.
     */
    fun checkVersion(version: String, kind: String) = ensureValid(VERSION.matches(version), "invalid_${kind}_version") {
        "Use a version label of 1 to 64 letters, digits, dots, plus signs, underscores or hyphens."
    }

    /**
     * Checks a digest: `sha256:<64 lower-case hex>`.
     *
     * @throws InvalidCatalogRequestException `invalid_skill_installation` otherwise.
     */
    fun checkDigest(digest: String) = ensureValid(DIGEST.matches(digest), "invalid_skill_installation") {
        "Use a sha256:<64 lowercase hex> digest."
    }

    /**
     * Checks a path (see the rules above).
     *
     * @param kind `skill` or `hook`.
     *
     * @throws InvalidCatalogRequestException `invalid_<kind>_path` otherwise.
     */
    fun checkPath(path: String, kind: String) {
        val segments = path.split('/')
        val valid = path.length in 1..MAX_PATH_LENGTH && segments.size <= MAX_PATH_DEPTH &&
            segments.all { segment ->
                SEGMENT.matches(segment) && !segment.endsWith('.') &&
                    segment.substringBefore('.').uppercase() !in WINDOWS_RESERVED
            }
        ensureValid(valid, "invalid_${kind}_path") {
            "File paths are relative, use '/' separators, at most $MAX_PATH_DEPTH segments and $MAX_PATH_LENGTH " +
                "characters, and hold only letters, digits, '.', '_', '+' or '-', without hidden, parent or " +
                "reserved segments."
        }
    }

    /**
     * Checks a file tree: 1 to [maxFiles] files, at most [maxBytes] of content, valid paths, unique ignoring case, and
     * no path both a file and a directory.
     *
     * @param files The files.
     * @param maxFiles The most files.
     * @param maxBytes The most bytes of content.
     * @param kind `skill` or `hook`, which names the error keys.
     * @param sha256 Hashes the contents.
     *
     * @return The files' entries, sorted by path.
     *
     * @throws InvalidCatalogRequestException `invalid_<kind>_artifact` or `invalid_<kind>_path`.
     * @throws ArtifactTooLargeException `<kind>_artifact_too_large`.
     */
    suspend fun entries(
        files: List<ArtifactFile>,
        maxFiles: Int,
        maxBytes: Long,
        kind: String,
        sha256: Sha256,
    ): List<FileEntry> {
        ensureValid(files.size in 1..maxFiles, "invalid_${kind}_artifact") { "An artifact holds 1 to $maxFiles files." }
        files.forEach { checkPath(it.path, kind) }
        if (files.sumOf { it.content.size.toLong() } > maxBytes) {
            throw ArtifactTooLargeException("${kind}_artifact_too_large", maxBytes)
        }
        val folded = files.map { it.path.lowercase() }
        ensureValid(folded.toSet().size == folded.size, "invalid_${kind}_artifact") {
            "File paths are unique, ignoring case."
        }
        val directories = folded.flatMap { path ->
            path.split('/').dropLast(1).runningReduce { parent, segment -> "$parent/$segment" }
        }.toSet()
        ensureValid(folded.none { it in directories }, "invalid_${kind}_artifact") {
            "A path cannot be both a file and a directory."
        }
        return files.sortedBy { it.path }.map { FileEntry(it.path, it.mode, it.content.size, sha256.hex(it.content)) }
    }

    /**
     * The lines the canonical digest hashes for the files: one per file sorted by path,
     * `<path>\u0000<mode>\u0000<size>\u0000<sha256 hex>\n`.
     *
     * @param entries The files' entries.
     *
     * @return The lines.
     */
    fun fileLines(entries: List<FileEntry>): String =
        entries.sortedBy { it.path }.joinToString("") {
            "${it.path}\u0000${it.mode.wire}\u0000${it.size}\u0000${it.sha256}\n"
        }

    /**
     * The canonical digest of a skill: SHA-256 over [fileLines], `sha256:<hex>`. A client recomputes it from the
     * downloaded files.
     *
     * @param entries The files' entries.
     * @param sha256 The hash.
     *
     * @return The digest.
     */
    suspend fun digest(entries: List<FileEntry>, sha256: Sha256): String =
        "sha256:" + sha256.hex(fileLines(entries).encodeToByteArray())

    /**
     * The entries of stored files, recomputed from their bytes: what a download is checked against.
     *
     * @param files The files.
     * @param sha256 The hash.
     *
     * @return Their entries, sorted by path.
     */
    suspend fun recompute(files: List<ArtifactFile>, sha256: Sha256): List<FileEntry> =
        files.sortedBy { it.path }.map { FileEntry(it.path, it.mode, it.content.size, sha256.hex(it.content)) }
}
