// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GatePullRequest
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.identity.domain.LinkedIdentities
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UserEmail
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.integrations.github.domain.CheckRun
import run.ostrom.server.integrations.github.domain.GateCheck
import run.ostrom.server.integrations.github.domain.GitHubApi
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubInstallation
import run.ostrom.server.integrations.github.domain.GitHubInstallationOrigin
import run.ostrom.server.integrations.github.domain.GitHubOriginsRepository
import run.ostrom.server.integrations.github.domain.GitHubPull
import run.ostrom.server.integrations.github.domain.GitHubRepository
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val OTHER_ORG: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
const val SHA = "0123456789abcdef0123456789abcdef01234567"
const val GITHUB = "https://github.com"
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

/** Who installed 42 (acme) and 43 (globex), as their `installation.created` webhooks say. */
val ACME_ORIGIN = GitHubInstallationOrigin(42, "acme-admin", "acme", "Organization")
val GLOBEX_ORIGIN = GitHubInstallationOrigin(43, "globex-admin", "globex", "Organization")

/**
 * GitHub as a map of pull requests and installations, recording every check it is sent. The person `acme-admin`
 * (code `code-acme`) reaches installation 42, `globex-admin` (code `code-globex`) reaches 43.
 */
class FakeGitHubApi : GitHubApi {
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
    val codes = mutableMapOf("code-acme" to "gho_acme", "code-globex" to "gho_globex")

    /** The installations each person's token reaches. */
    val reachable = mutableMapOf("gho_acme" to setOf(42L), "gho_globex" to setOf(43L))

    /** GitHub not answering the exchange of the install's code. */
    var down = false

    override suspend fun userToken(code: String): String? =
        if (down) throw GitHubCallException("GitHub did not answer POST /login/oauth/access_token.") else codes[code]

    override suspend fun userInstallations(userToken: String): Set<Long> = reachable[userToken].orEmpty()

    /** The login behind each person's token. */
    val logins = mutableMapOf("gho_acme" to "acme-admin", "gho_globex" to "globex-admin")

    override suspend fun userLogin(userToken: String): String = logins.getValue(userToken)
}

/** Who installed each installation and the deliveries handled, in memory. */
class MemoryGitHubOrigins : GitHubOriginsRepository {
    val byId = mutableMapOf<Long, GitHubInstallationOrigin>()
    val deliveries = mutableSetOf<String>()

    override suspend fun saveOrigin(origin: GitHubInstallationOrigin, at: Instant) {
        byId[origin.installationId] = origin
    }

    override suspend fun origin(installationId: Long): GitHubInstallationOrigin? = byId[installationId]

    override suspend fun claimDelivery(deliveryId: String, at: Instant): Boolean = deliveries.add(deliveryId)

    override suspend fun releaseDelivery(deliveryId: String) {
        deliveries.remove(deliveryId)
    }
}

/**
 * The repository in memory, with the same rules (one installation per organization, single-use states).
 *
 * @property origins Who installed each installation: forgotten with it.
 */
class MemoryGitHubRepository(val origins: MemoryGitHubOrigins = MemoryGitHubOrigins()) : GitHubRepository {
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

    override suspend fun forgetInstallation(installationId: Long) {
        installations.removeAll { it.installationId == installationId }
        origins.byId.remove(installationId)
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

/**
 * The identity module's users as the GitHub integration reads them: by the GitHub account each one signed in with.
 *
 * @param linked The login of the person linked to each GitHub user id.
 */
class LinkedGitHubUsers(private val linked: Map<Long, String>) : UsersRepository, LinkedIdentities {
    override suspend fun findByIdentity(issuer: String, subject: String): User? =
        linked[subject.toLong()]?.takeIf { issuer == IdentityProvider.GITHUB.key }
            ?.let { User(Uuid.random(), it, it, null, null, NOW) }

    override suspend fun get(id: Uuid): User? = unused()

    override suspend fun findByVerifiedEmail(email: String): User? = unused()

    override suspend fun loginTaken(login: String): Boolean = unused()


    override suspend fun findByLogin(login: String): User? = unused()

    override suspend fun getMany(ids: Collection<Uuid>): List<User> = unused()

    override suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = unused()

    override suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = unused()

    override suspend fun listEmails(userId: Uuid): List<UserEmail> = unused()

    override suspend fun listIssuers(userId: Uuid): List<String> = unused()

    override suspend fun subjectsOf(login: String, issuer: String): List<String> =
        if (issuer != IdentityProvider.GITHUB.key) {
            emptyList()
        } else {
            linked.filterValues { it.equals(login, ignoreCase = true) }.keys.map { it.toString() }
        }

    private fun unused(): Nothing = error("The GitHub integration only reads a user by their GitHub account.")
}
