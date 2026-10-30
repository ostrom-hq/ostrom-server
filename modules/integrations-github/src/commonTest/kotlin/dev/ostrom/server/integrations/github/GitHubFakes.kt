// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.integrations.github.domain.CheckRun
import dev.ostrom.server.integrations.github.domain.GateCheck
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubPull
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val OTHER_ORG: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
const val SHA = "0123456789abcdef0123456789abcdef01234567"
val NOW: Instant = Instant.parse("2026-10-30T04:00:00Z")

class SettableClock(var now: Instant = NOW) : Clock {
    override fun now(): Instant = now
}

class RecordingLogger : Logger {
    val lines = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += level to message
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/**
 * GitHub as a map of pull requests and installations, recording every check it is sent. The person `acme-admin`
 * (code `code-acme`) reaches installation 42, `globex-admin` (code `code-globex`) reaches 43.
 */
class FakeGitHubApi : GitHubApi {
    init {
        codes["code-acme"] = "gho_acme"
        codes["code-globex"] = "gho_globex"
        reachable["gho_acme"] = setOf(42L)
        reachable["gho_globex"] = setOf(43L)
    }

    val pulls = mutableMapOf<Pair<String, Int>, GitHubPull>()
    val accounts = mutableMapOf<Long, String>()
    val published = mutableListOf<Triple<String, CheckRun, Long?>>()
    var failure: Exception? = null
    private var nextId = 100L

    override suspend fun pullRequest(installationId: Long, repo: String, number: Int): GitHubPull? {
        failure?.let { throw it }
        return pulls[repo to number]
    }

    override suspend fun publishCheck(installationId: Long, repo: String, check: CheckRun, checkRunId: Long?): Long {
        published += Triple(repo, check, checkRunId)
        return checkRunId ?: nextId++
    }

    override suspend fun installationAccount(installationId: Long): String? = accounts[installationId]

    /** The OAuth codes GitHub gives back, each the token of a person. */
    val codes = mutableMapOf<String, String>()

    /** The installations each person's token reaches. */
    val reachable = mutableMapOf<String, Set<Long>>()

    /** GitHub not answering the exchange of the install's code. */
    var down = false

    override suspend fun userToken(code: String): String? =
        if (down) throw GitHubCallException("GitHub did not answer POST /login/oauth/access_token.") else codes[code]

    override suspend fun userInstallations(userToken: String): Set<Long> = reachable[userToken].orEmpty()
}

/** The repository in memory, with the same rules (one installation per organization, single-use states). */
class MemoryGitHubRepository : GitHubRepository {
    val installations = mutableListOf<GitHubInstallation>()
    val states = mutableMapOf<String, Pair<Uuid, Instant>>()
    val checks = mutableMapOf<Pair<Uuid, Uuid>, GateCheck>()

    override suspend fun installation(orgId: Uuid) = installations.firstOrNull { it.orgId == orgId }

    override suspend fun installationById(installationId: Long) =
        installations.firstOrNull { it.installationId == installationId }

    override suspend fun saveInstallation(installation: GitHubInstallation, at: Instant) {
        installations.removeAll { it.orgId == installation.orgId }
        installations += installation
    }

    override suspend fun removeInstallation(installation: GitHubInstallation) {
        installations.removeAll { it.installationId == installation.installationId }
    }

    override suspend fun saveState(orgId: Uuid, stateHash: String, by: String, expiresAt: Instant) {
        states[stateHash] = orgId to expiresAt
    }

    override suspend fun takeState(stateHash: String, now: Instant): Uuid? =
        states.remove(stateHash)?.takeIf { it.second > now }?.first

    override suspend fun check(orgId: Uuid, gateId: Uuid) = checks[orgId to gateId]

    override suspend fun saveCheck(orgId: Uuid, check: GateCheck) {
        checks[orgId to check.gateId] = check
    }
}

/** A merge gate of `acme/api` PR #6 into [base], asked by `dev`. */
fun mergeGate(
    status: GateStatus = GateStatus.PENDING,
    base: String = "main",
    repo: String? = "acme/api",
    pullRequest: Boolean = true,
) = Gate(
    id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"),
    orgId = ORG,
    key = "k",
    subject = GateSubject(
        repo = repo,
        actionClass = "push-main",
        command = "gh pr merge 6",
        scope = "session:-",
        pullRequest = GatePullRequest(6, SHA, base).takeIf { pullRequest },
    ),
    node = null,
    label = "Merge into main",
    mode = GateMode.ENFORCED,
    status = status,
    askedBy = listOf("dev"),
    askedVia = emptySet(),
    sessionIds = emptyList(),
    shownDigest = "shown",
    evidence = emptyList(),
    decidedBy = "lead".takeIf { status != GateStatus.PENDING },
    receiptId = "rcpt_1".takeIf { status != GateStatus.PENDING },
    createdAt = NOW,
)

fun request(approver: String, status: RequestStatus = RequestStatus.PENDING) =
    ApprovalRequest(Uuid.random(), mergeGate().id, ORG, approver, status, createdAt = NOW)

/** Thrown by the fake API to test GitHub failing. */
fun gitHubDown() = GitHubCallException("GitHub did not answer GET /repos/acme/api/pulls/6.")
