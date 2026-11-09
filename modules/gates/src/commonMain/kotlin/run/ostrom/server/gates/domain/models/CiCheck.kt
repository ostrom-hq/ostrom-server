// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.models

import kotlin.time.Instant

/**
 * A check that a CI ran on a commit, as the forge records it (GitHub's check run), never as a workstation reports it:
 * the proof behind the `ci` attestation of a receipt's check node.
 *
 * @property id The forge's id of the run.
 * @property name Its name (the CI job or check).
 * @property headSha The commit it ran on.
 * @property conclusion How it completed: `success`, `failure`, `neutral`, `cancelled`, `skipped`, `timed_out` or
 *   `action_required`.
 * @property app Who ran it (the GitHub App's slug, `github-actions` for GitHub Actions).
 * @property url Its page on the forge, when known.
 * @property completedAt When it completed, when known.
 */
data class CiCheck(
    val id: Long,
    val name: String,
    val headSha: String,
    val conclusion: String,
    val app: String,
    val url: String?,
    val completedAt: Instant?,
)
