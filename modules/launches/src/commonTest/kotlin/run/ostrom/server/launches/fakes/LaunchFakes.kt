// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.fakes

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.workflows.WorkflowDirectory
import run.ostrom.server.launches.domain.models.HooksCheck
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.launches.domain.services.AgentTokenService
import run.ostrom.server.launches.domain.services.HooksCheckRules
import run.ostrom.server.launches.domain.services.LaunchModelRules
import run.ostrom.server.launches.domain.services.LauncherResolver
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val OTHER_ORG: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
val ADA: Launcher = Launcher(ORG, Uuid.parse("000000ad-0000-0000-0000-000000000000"), "ada", OrgRole.MEMBER)

/** The member id of a person in the fakes' organizations: their user id's first half, then `ae`. */
fun memberOf(userId: Uuid): Uuid = Uuid.fromLongs(userId.toLongs { high, _ -> high }, 0xAE)

class FixedClock(var now: Instant = Instant.parse("2026-10-30T10:00:00.123456Z")) : Clock {
    override fun now(): Instant = now
}

class InMemoryLaunches : LaunchesRepository {
    val launches = mutableListOf<Launch>()

    val limits = mutableListOf<LaunchLimit>()

    /** When set, the limit refuses every launch with this wait. */
    var refusal: Duration? = null

    /** Stores a launch, as the tests of the readers set them up. */
    fun create(launch: Launch) {
        launches += launch
    }

    override suspend fun create(launch: Launch, limit: LaunchLimit): Duration? {
        limits += limit
        return refusal ?: null.also { launches += launch }
    }

    override suspend fun find(orgId: Uuid, id: String): Launch? =
        launches.firstOrNull { it.orgId == orgId && it.id == id }

    override suspend fun purge(before: Instant): Long {
        val gone = launches.filter { it.expiresAt < before }
        launches -= gone.toSet()
        return gone.size.toLong()
    }
}

/** The people of [launchers], by user id; [roles] their role now (the launcher's role when not set). */
class FakeLaunchers(
    private val launchers: Map<String, Launcher>,
    val roles: MutableMap<Uuid, OrgRole?> = mutableMapOf(),
) : LauncherResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal): Launcher? = launchers[principal.subjectId]

    override suspend fun member(orgId: Uuid, userId: Uuid): OrgPrincipal? {
        val role = if (userId in roles) {
            roles[userId]
        } else {
            launchers.values.firstOrNull { it.orgId == orgId && it.userId == userId }?.role
        }
        return role?.let { OrgPrincipal(orgId, memberOf(userId), it) }
    }

    /** The members of [launchers], by member id: their key. */
    override suspend fun owner(orgId: Uuid, memberId: Uuid): Launcher? =
        launchers[memberId.toString()]?.takeIf { it.orgId == orgId }

    /** The person of [launchers] with that login in that organization. */
    override suspend fun colleague(orgId: Uuid, login: String): Launcher? =
        launchers.values.firstOrNull { it.orgId == orgId && it.login == login }
}

/** Records each token issued and answers `osa_<launch>`. */
class RecordingTokens : AgentTokenService {
    val issued = mutableListOf<Triple<Launcher, String, Duration>>()

    override suspend fun issue(launcher: Launcher, launchId: String, lifetime: Duration): String {
        issued += Triple(launcher, launchId, lifetime)
        return "osa_$launchId"
    }

    val revoked = mutableListOf<String>()

    override suspend fun revoke(launchId: String): Long {
        revoked += launchId
        return 1
    }
}

/** Refuses the models of [refused] with their reason. */
class FakeModelRules(private val refused: Map<String, String> = emptyMap()) : LaunchModelRules {
    val asked = mutableListOf<Triple<Uuid, String, String>>()

    override suspend fun refusal(launcher: Launcher, model: String): String? {
        asked += Triple(launcher.orgId, launcher.login, model)
        return refused[model]
    }
}

/** The organization's workflows by id, and every lookup asked of them. */
class FakeWorkflows(private val names: Map<Pair<Uuid, String>, String>) : WorkflowDirectory {
    val asked = mutableListOf<Pair<Uuid, String>>()

    override suspend fun name(orgId: Uuid, workflowId: String): String? {
        asked += orgId to workflowId
        return names[orgId to workflowId]
    }
}

/** Answers what it is set to, and remembers what it was asked. */
class FakeHooksCheckRules(var afterSeconds: Int = 15, var stopAgent: Boolean = false) : HooksCheckRules {
    val asked = mutableListOf<Pair<Uuid, String?>>()

    override suspend fun check(orgId: Uuid, repo: String?): HooksCheck {
        asked += orgId to repo
        return HooksCheck(afterSeconds, stopAgent)
    }
}

/** Keeps what was written to the audit log. */
class RecordingAudit : OrgAuditTrail {
    val records = mutableListOf<OrgAuditRecord>()

    override suspend fun record(record: OrgAuditRecord) {
        records += record
    }
}
