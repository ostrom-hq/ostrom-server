// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.models

/**
 * A package as the list shows it: what [StoredPackage] keeps beside its envelope, and its pin.
 *
 * @property version The version.
 * @property digest The envelope digest.
 * @property contentDigest The content digest.
 * @property repo The repository, or null for the organization.
 * @property compiledAt When it was compiled (`toISOString`).
 * @property counts What it holds.
 * @property revoked True once revoked.
 * @property revokedBy Who revoked it.
 * @property revokeReason Why.
 * @property pinned True when its repository pins it: the sessions there receive it.
 */
data class ListedPackage(
    val version: String,
    val digest: String,
    val contentDigest: String,
    val repo: String?,
    val compiledAt: String,
    val counts: Map<String, Int>,
    val revoked: Boolean,
    val revokedBy: String?,
    val revokeReason: String?,
    val pinned: Boolean,
)
