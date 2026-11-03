// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.rules.domain.models.BranchProtection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.io.encoding.Base64

/** GitHub's answers about a repository's contents and branch protection, as [HttpGitHubContentsApi] reads them. */
internal object GitHubContentsJson {
    // The analysis reads CODEOWNERS and a package.json: 512 KB is far beyond either.
    private const val MAX_FILE_BYTES = 512 * 1024

    /**
     * The text of a small file; a directory, a large file (GitHub sends no content past 1 MB) or a binary is null.
     *
     * @param file GitHub's answer.
     * @return The text, or null.
     */
    fun text(file: Content): String? {
        val readable = file.type == "file" && file.encoding == "base64" && file.size <= MAX_FILE_BYTES
        val bytes = if (readable) decoded(file.content) else null
        return bytes?.let { decoded -> decoded.decodeToString().takeUnless { '\u0000' in it } }
    }

    private fun decoded(content: String): ByteArray? = try {
        Base64.Mime.decode(content)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * The protection of a branch, from its classic protection and the rulesets that apply to it.
     *
     * @param classic The branch, with its classic protection.
     * @param rules The rules of the rulesets on the branch.
     * @return The protection.
     */
    fun protectionOf(classic: Branch, rules: List<BranchRule>): BranchProtection {
        val reviews = rules.filter { it.type == "pull_request" }.map { it.parameters }
        // The strictest ruleset wins; none asks for no approval.
        val approvals = reviews.map { it.int("required_approving_review_count") }.fold(0, ::maxOf)
        val checks = classic.protection.requiredStatusChecks.contexts +
            rules.filter { it.type == "required_status_checks" }.flatMap { contexts(it.parameters) }
        return BranchProtection(
            protected = classic.protected || rules.isNotEmpty(),
            requiredApprovals = approvals,
            codeOwnerReviews = reviews.any { it.flag("require_code_owner_review") },
            requiredChecks = checks.distinct(),
            // A classic protection refuses force-pushes unless an administrator allowed them, which only
            // `administration: read` would show.
            blocksForcePush = classic.protected || rules.any { it.type == "non_fast_forward" },
        )
    }

    // Read leniently: a ruleset's parameters are GitHub's to extend, and an unknown shape reads as nothing.
    private fun contexts(parameters: JsonObject): List<String> =
        (parameters["required_status_checks"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("context") as? JsonPrimitive)?.content }

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    @Serializable
    internal data class Sha(val sha: String)

    @Serializable
    internal data class Commit(val tree: Sha)

    @Serializable
    internal data class BranchCommit(val commit: Commit)

    @Serializable
    internal data class RequiredChecks(val contexts: List<String> = emptyList())

    @Serializable
    internal data class Protection(
        @SerialName("required_status_checks") val requiredStatusChecks: RequiredChecks = RequiredChecks(),
    )

    @Serializable
    internal data class Branch(
        val commit: BranchCommit,
        val protected: Boolean = false,
        val protection: Protection = Protection(),
    )

    @Serializable
    internal data class TreeEntry(val path: String, val type: String)

    @Serializable
    internal data class Tree(val tree: List<TreeEntry>, val truncated: Boolean = false)

    @Serializable
    internal data class Content(
        val type: String,
        val size: Long = 0,
        val encoding: String? = null,
        val content: String = "",
    )

    @Serializable
    internal data class BranchRule(val type: String, val parameters: JsonObject = JsonObject(emptyMap()))
}
