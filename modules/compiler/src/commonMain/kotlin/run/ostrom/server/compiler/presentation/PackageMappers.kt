// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.presentation

import run.ostrom.server.compiler.domain.models.ListedPackage

/** Maps a listed package to the API (contract `PackageSummary`). */
fun ListedPackage.toSummary() = PackageSummary(
    version = version,
    digest = digest,
    contentDigest = contentDigest,
    repo = repo,
    compiledAt = compiledAt,
    pinned = pinned,
    revoked = revoked,
    revokedBy = revokedBy,
    revokeReason = revokeReason,
    counts = counts,
)
