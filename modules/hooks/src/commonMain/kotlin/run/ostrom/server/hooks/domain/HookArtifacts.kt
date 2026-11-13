// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.ArtifactIntegrityException
import run.ostrom.server.skills.domain.ArtifactTree
import run.ostrom.server.skills.domain.FileEntry
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.InvalidCatalogRequestException
import run.ostrom.server.skills.domain.Sha256
import run.ostrom.server.skills.domain.ValidatedArtifact

/** The bounds of the hook catalog (TeamCode's `HookStore` and `HookArtifacts`). */
object HookLimits {
    /** Hooks per organization. */
    const val HOOKS: Long = 100

    /** Versions per hook. */
    const val VERSIONS: Long = 200

    /** Files per artifact. */
    const val FILES: Int = 50

    /** Bytes of content per artifact: 1 MiB. */
    const val BYTES: Long = 1024L * 1024

    /** The longest matcher. */
    const val MATCHER_LENGTH: Int = 256

    /** A hook's timeout when its definition gives none. */
    const val DEFAULT_TIMEOUT_SECONDS: Int = 60

    /** The longest timeout. */
    const val MAX_TIMEOUT_SECONDS: Int = 600

    /** Explicit decisions listed per hook. */
    const val ROWS: Int = 1_000

    /** The key of the catalog's refusals: `invalid_hook_name`, `hook_artifact_too_large`… */
    const val KIND: String = "hook"
}

/**
 * The definition and the artifact of a hook version (TeamCode's `HookArtifacts`): names, version labels and paths
 * follow the skill catalog's rules, and the definition is part of the digest, so a client verifies what it will run
 * and when.
 */
object HookArtifacts {
    private const val INVALID = "invalid_hook_definition"

    /**
     * Checks a definition alone: events, matcher, timeout and the command's path.
     *
     * @throws InvalidCatalogRequestException `invalid_hook_definition`, or `invalid_hook_path` for the command.
     */
    fun checkDefinition(definition: HookDefinition) {
        ensure(definition.events.isNotEmpty()) { "A hook listens to at least one event." }
        val matcher = definition.matcher
        val printable = matcher == null ||
            (matcher.length in 1..HookLimits.MATCHER_LENGTH && matcher.none { it.isISOControl() })
        ensure(printable) {
            "A matcher is 1 to ${HookLimits.MATCHER_LENGTH} characters without control characters."
        }
        ensure(definition.timeoutSeconds in 1..HookLimits.MAX_TIMEOUT_SECONDS) {
            "A timeout is 1 to ${HookLimits.MAX_TIMEOUT_SECONDS} seconds."
        }
        ArtifactTree.checkPath(definition.command, HookLimits.KIND)
    }

    /**
     * Checks a definition and its files; the command must be one of them, published as executable.
     *
     * @return The validated artifact, its digest covering the definition.
     *
     * @throws InvalidCatalogRequestException For a malformed definition, path or tree.
     * @throws run.ostrom.server.skills.domain.ArtifactTooLargeException `hook_artifact_too_large`.
     */
    suspend fun validate(definition: HookDefinition, files: List<ArtifactFile>, sha256: Sha256): ValidatedArtifact {
        checkDefinition(definition)
        val entries = ArtifactTree.entries(files, HookLimits.FILES, HookLimits.BYTES, HookLimits.KIND, sha256)
        ensure(entries.any { it.path == definition.command && it.mode == FileMode.EXECUTABLE }) {
            "The command must be an executable file of the artifact."
        }
        return ValidatedArtifact(digest(definition, entries, sha256), files.sortedBy { it.path }, entries)
    }

    /**
     * The canonical digest: SHA-256 over one definition line,
     * `hook\0<events by wire name, sorted, comma-separated>\0<matcher or empty>\0<command>\0<timeout>\n`,
     * followed by the skill catalog's file lines ([ArtifactTree.fileLines]), rendered as `sha256:<hex>`.
     *
     * @param definition The definition.
     * @param entries The files' entries.
     * @param sha256 The hash.
     *
     * @return The digest.
     */
    suspend fun digest(definition: HookDefinition, entries: List<FileEntry>, sha256: Sha256): String {
        val head = "hook\u0000${definition.wireEvents.joinToString(",")}\u0000${definition.matcher.orEmpty()}" +
            "\u0000${definition.command}\u0000${definition.timeoutSeconds}\n"
        return "sha256:" + sha256.hex((head + ArtifactTree.fileLines(entries)).encodeToByteArray())
    }

    /**
     * The download of a stored version, its files checked against its digest.
     *
     * @param name The hook.
     * @param version The version.
     * @param files Its stored files.
     * @param sha256 The hash.
     *
     * @return The files with their entries recomputed from their bytes.
     *
     * @throws ArtifactIntegrityException `hook_integrity` when they no longer match: never served.
     */
    suspend fun verified(
        name: String,
        version: HookVersion,
        files: List<ArtifactFile>,
        sha256: Sha256,
    ): HookDownload {
        val entries = ArtifactTree.recompute(files, sha256)
        if (digest(version.definition, entries, sha256) != version.digest) {
            throw ArtifactIntegrityException("hook_integrity")
        }
        return HookDownload(name, version, entries, files.sortedBy { it.path })
    }

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidCatalogRequestException(INVALID, message())
    }
}

/**
 * A key asks to deliver a pinned hook to all members (403 `hook_audience_requires_administrator`): the
 * administrator's pin approved the code and who runs it together, so only an administrator widens it afterwards.
 */
class HookAudienceException : OstromException(
    ErrorKind.FORBIDDEN,
    "This hook is pinned: only an administrator can deliver it to all members. Publish without allMembers, " +
        "or ask an administrator.",
    "hook_audience_requires_administrator",
)
