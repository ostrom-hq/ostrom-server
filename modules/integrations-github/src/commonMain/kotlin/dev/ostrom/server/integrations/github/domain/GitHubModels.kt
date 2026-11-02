// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The name of the check run Ostrom publishes; a ruleset requires it on the protected branch. */
const val CHECK_NAME: String = "ostrom-gate"

/**
 * A pull request as GitHub describes it.
 *
 * @property number Its number.
 * @property headSha The head commit.
 * @property base The branch it merges into.
 * @property open False once closed or merged: no yes is asked for it any more.
 * @property author The login of who opened it.
 */
data class GitHubPull(val number: Int, val headSha: String, val base: String, val open: Boolean, val author: String)

/**
 * The `ostrom-gate` check run as published on a commit.
 *
 * @property headSha The commit.
 * @property status `in_progress` or `completed`.
 * @property conclusion `success` or `failure` once completed.
 * @property title The one line GitHub shows next to the check.
 * @property summary The details.
 * @property detailsUrl Where the check links: the request on the page of the yes.
 * @property externalId The gate.
 */
data class CheckRun(
    val headSha: String,
    val status: String,
    val conclusion: String?,
    val title: String,
    val summary: String,
    val detailsUrl: String,
    val externalId: String,
)

/**
 * What Ostrom published for a gate, to update the same check run when the gate is answered.
 *
 * @property gateId The gate.
 * @property repo The repository (`owner/name`).
 * @property sha The commit the check is on.
 * @property checkRunId GitHub's id of the check run.
 * @property conclusion The last conclusion published, or null while waiting.
 * @property updatedAt When it was last published.
 */
data class GateCheck(
    val gateId: Uuid,
    val repo: String,
    val sha: String,
    val checkRunId: Long,
    val conclusion: String?,
    val updatedAt: Instant,
)

/**
 * An organization's installation of the GitHub App.
 *
 * @property orgId The organization.
 * @property installationId GitHub's id of the installation.
 * @property account The GitHub account (user or organization) it is installed on.
 */
data class GitHubInstallation(val orgId: Uuid, val installationId: Long, val account: String)

/**
 * GitHub refused or failed a call: the message names the call, never a token or a body.
 *
 * @param message What failed.
 * @param cause The transport or decoding failure, if any.
 */
class GitHubCallException(message: String, cause: Throwable? = null) : Exception(message, cause)
