// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.fakes

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.models.Launcher
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.domain.services.AgentTokenService
import dev.ostrom.server.launches.domain.services.LaunchModelRules
import dev.ostrom.server.launches.domain.services.LauncherResolver
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
val OTHER_ORG: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
val ADA: Launcher = Launcher(ORG, Uuid.parse("000000ad-0000-0000-0000-000000000000"), "ada", OrgRole.MEMBER)

class FixedClock(var now: Instant = Instant.parse("2026-10-30T10:00:00.123456Z")) : Clock {
    override fun now(): Instant = now
}

class InMemoryLaunches : LaunchesRepository {
    val launches = mutableListOf<Launch>()

    override suspend fun create(launch: Launch) {
        launches += launch
    }

    override suspend fun find(orgId: Uuid, id: String): Launch? =
        launches.firstOrNull { it.orgId == orgId && it.id == id }
}

/** The people of [launchers], by user id; [roles] their role now (the launcher's role when not set). */
class FakeLaunchers(
    private val launchers: Map<String, Launcher>,
    val roles: MutableMap<Uuid, OrgRole?> = mutableMapOf(),
) : LauncherResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal): Launcher? = launchers[principal.subjectId]

    override suspend fun role(orgId: Uuid, userId: Uuid): OrgRole? = if (userId in roles) {
        roles[userId]
    } else {
        launchers.values.firstOrNull { it.orgId == orgId && it.userId == userId }?.role
    }
}

/** Records each token issued and answers `osa_<launch>`. */
class RecordingTokens : AgentTokenService {
    val issued = mutableListOf<Triple<Launcher, String, Duration>>()

    override suspend fun issue(launcher: Launcher, launchId: String, lifetime: Duration): String {
        issued += Triple(launcher, launchId, lifetime)
        return "osa_$launchId"
    }
}

/** Refuses the models of [refused] with their reason. */
class FakeModelRules(private val refused: Map<String, String> = emptyMap()) : LaunchModelRules {
    val asked = mutableListOf<Triple<Uuid, String, String>>()

    override suspend fun refusal(orgId: Uuid, login: String, model: String): String? {
        asked += Triple(orgId, login, model)
        return refused[model]
    }
}
