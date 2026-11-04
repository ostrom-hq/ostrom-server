// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.DeleteRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import dev.ostrom.server.runners.domain.ListRegisteredRunnersUseCaseImpl
import dev.ostrom.server.runners.domain.ListedRunner
import dev.ostrom.server.runners.domain.MachineWork
import dev.ostrom.server.runners.domain.NewRunner
import dev.ostrom.server.runners.domain.RegisterRunnerUseCaseImpl
import dev.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCaseImpl
import dev.ostrom.server.runners.domain.RegisteredRunnerKey
import dev.ostrom.server.runners.domain.RegisteredRunner
import dev.ostrom.server.runners.domain.RegisteredRunnersRepository
import dev.ostrom.server.runners.domain.RunnerAdminRequiredException
import dev.ostrom.server.runners.domain.RunnerLimitException
import dev.ostrom.server.runners.domain.RunnerNameTakenException
import dev.ostrom.server.runners.domain.RunnerNotFoundException
import dev.ostrom.server.runners.domain.RunnerRegistration
import dev.ostrom.server.runners.domain.RunnerRegistrationOutcome
import dev.ostrom.server.runners.domain.RunnerUnauthenticatedException
import dev.ostrom.server.runners.domain.constantTimeEquals
import dev.ostrom.server.runners.fakes.FakeRunnerTokens
import dev.ostrom.server.runners.fakes.FakeRunnerWorkSignals
import dev.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import dev.ostrom.server.runners.fakes.InMemoryRunnerAssignments
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.SettableClock
import dev.ostrom.server.runners.presentation.RegisteredRunnerDto
import dev.ostrom.server.runners.presentation.RunnerDto
import dev.ostrom.server.runners.presentation.callerOf
import dev.ostrom.server.runners.presentation.toDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The registration and the listing of an organization's runners, on a fake repository. */
class RegisteredRunnersTest {
    private val clock = SettableClock()
    private val repository = InMemoryRegisteredRunners()
    private val tokens = FakeRunnerTokens()
    private val register = RegisterRunnerUseCaseImpl(repository, tokens, clock)
    private val list = ListRegisteredRunnersUseCaseImpl(repository, clock)
    private val admin = Caller(ORG_A, "dana", admin = true)

    @Test
    fun anAdminRegistersARunnerAndGetsItsKeyOnceWhileOnlyItsHashIsStored() = runTest {
        val created = register(admin, RunnerRegistration("  build-1 ", listOf("linux", "gpu", "linux"), "1.2.0"))

        assertEquals("token-1", created.key)
        val stored = repository.runners.single()
        assertEquals(created.runner, stored)
        assertEquals("build-1", stored.name)
        assertEquals(listOf("linux", "gpu"), stored.labels)
        assertEquals("1.2.0", stored.version)
        assertEquals("hash(token-1)", stored.keyHash)
        assertEquals("dana", stored.registeredBy)
        assertEquals(ORG_A, stored.orgId)
        assertEquals(clock.now, stored.createdAt)
        assertEquals(null, stored.lastSeenAt)
        assertEquals(RegisterRunnerUseCaseImpl.MAX_RUNNERS, repository.lastMax)
        // The key never reaches a log line through toString; its hash may.
        assertFalse("key=token-1" in created.toString())
        assertTrue("key=***" in created.toString())
    }

    @Test
    fun aMemberWhoIsNeitherOwnerNorAdminCannotRegister() = runTest {
        assertFailsWith<RunnerAdminRequiredException> {
            register(Caller(ORG_A, "alice"), RunnerRegistration("build", emptyList(), null))
        }
        assertTrue(repository.runners.isEmpty())
        assertTrue(tokens.minted.isEmpty())
    }

    @Test
    fun aMalformedNameLabelOrVersionIsRefusedBeforeAnyKeyIsMinted() = runTest {
        val refused = listOf(
            RunnerRegistration("   ", emptyList(), null),
            RunnerRegistration("x".repeat(65), emptyList(), null),
            RunnerRegistration("bad\u0007name", emptyList(), null),
            RunnerRegistration("build\u200B", emptyList(), null),
            RunnerRegistration("\u202Ebuild", emptyList(), null),
            RunnerRegistration("build", (1..17).map { "l$it" }, null),
            RunnerRegistration("build", listOf("ok", "-dash"), null),
            RunnerRegistration("build", listOf("x".repeat(65)), null),
            RunnerRegistration("build", listOf("has space"), null),
            RunnerRegistration("build", emptyList(), ""),
            RunnerRegistration("build", emptyList(), "1.0 beta"),
        )
        for (registration in refused) {
            assertFailsWith<InvalidCloudSessionRequestException>(registration.toString()) {
                register(admin, registration)
            }
        }
        assertTrue(tokens.minted.isEmpty())
        val accepted = register(
            admin,
            RunnerRegistration("x".repeat(64), (1..16).map { "os:linux-$it" }, "v2.0.0+build_7"),
        )
        assertEquals(16, accepted.runner.labels.size)
        assertEquals(null, register(admin, RunnerRegistration("bare", emptyList(), null)).runner.version)
    }

    @Test
    fun aTakenNameAndAFullOrganizationAreRefused() = runTest {
        repository.outcome = RunnerRegistrationOutcome.NAME_TAKEN
        assertFailsWith<RunnerNameTakenException> { register(admin, RunnerRegistration("b", emptyList(), null)) }
        repository.outcome = RunnerRegistrationOutcome.LIMIT_REACHED
        val full = assertFailsWith<RunnerLimitException> {
            register(admin, RunnerRegistration("b", emptyList(), null))
        }
        assertEquals("An organization registers at most 100 runners.", full.message)
    }

    @Test
    fun anAdminRemovesARunnerOfTheOrganizationOnly() = runTest {
        val delete = DeleteRunnerUseCaseImpl(repository)
        val mine = runner("a", lastSeenAt = null)
        val theirs = runner("b", orgId = ORG_B, lastSeenAt = null)
        repository.runners += listOf(mine, theirs)

        assertFailsWith<RunnerAdminRequiredException> { delete(Caller(ORG_A, "alice"), mine.id) }
        assertFailsWith<RunnerNotFoundException> { delete(admin, theirs.id) }
        assertFailsWith<RunnerNotFoundException> { delete(admin, Uuid.random()) }
        assertEquals(listOf(mine, theirs), repository.runners)

        delete(admin, mine.id)
        assertEquals(listOf(theirs), repository.runners)
    }

    @Test
    fun theListShowsARunnerOnlineWhenItReportedWithinNinetySeconds() = runTest {
        val fresh = runner("a", lastSeenAt = clock.now - 90.seconds)
        val silent = runner("b", lastSeenAt = clock.now - 91.seconds)
        val never = runner("c", lastSeenAt = null)
        repository.runners += listOf(fresh, silent, never, runner("d", orgId = ORG_B, lastSeenAt = clock.now))

        assertEquals(
            listOf(ListedRunner(fresh, true), ListedRunner(silent, false), ListedRunner(never, false)),
            list(admin),
        )
        assertEquals(RegisterRunnerUseCaseImpl.MAX_RUNNERS, repository.lastLimit)
    }

    @Test
    fun aRunnerMapsToTheContractAndItsKeyOnlyAppearsAtRegistration() {
        val seen = runner("build", lastSeenAt = clock.now)
        assertEquals(
            RunnerDto(seen.id.toString(), "build", listOf("linux"), "online", clock.now.toString(), "1.0"),
            ListedRunner(seen, online = true).toDto(),
        )
        val unseen = seen.copy(lastSeenAt = null, version = null)
        assertEquals(
            RunnerDto(seen.id.toString(), "build", listOf("linux"), "offline", null, null),
            ListedRunner(unseen, online = false).toDto(),
        )
        val created = NewRunner(unseen, "ostrom_runner_secret").toDto()
        assertEquals(
            RegisteredRunnerDto(
                seen.id.toString(), "build", listOf("linux"), "offline", null, null, "ostrom_runner_secret",
            ),
            created,
        )
        assertFalse("secret" in created.toString())
    }

    @Test
    fun anOwnerOrAnAdminIsTheOrganizationsAdministrator() = runTest {
        val principal = AuthenticatedPrincipal("someone", PrincipalKind.USER, setOf("cloud:read"))
        for ((role, isAdmin) in OrgRole.entries.associateWith { it == OrgRole.OWNER || it == OrgRole.ADMIN }) {
            val resolver = object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?) =
                    OrgPrincipal(ORG_A, MEMBER, role)
            }
            assertEquals(Caller(ORG_A, MEMBER.toString(), isAdmin), resolver.callerOf(principal, "cloud:read"), "$role")
        }
    }

    private fun runner(name: String, orgId: Uuid = ORG_A, lastSeenAt: Instant?) = RegisteredRunner(
        id = Uuid.random(),
        orgId = orgId,
        name = name,
        labels = listOf("linux"),
        version = "1.0",
        keyHash = "hash($name)",
        registeredBy = "dana",
        createdAt = clock.now,
        lastSeenAt = lastSeenAt,
    )

    private fun newHeartbeat() = RegisteredRunnerHeartbeatUseCaseImpl(
        repository,
        tokens,
        clock,
        InMemoryRunnerAssignments(),
        InMemoryCloudSessionsRepository(),
        FakeRunnerWorkSignals(),
    ) { error("No execution to plan") }

    private companion object {
        val MEMBER: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    }

    @Test
    fun aHeartbeatWithTheRunnersKeyMarksItSeenUntilTheWindowPasses() = runTest {
        val heartbeat = newHeartbeat()
        val created = register(admin, RunnerRegistration("build-1", emptyList(), null))
        assertFalse(list(admin).single().online, "Never seen: offline")

        assertEquals(MachineWork(emptyList(), emptyList()), heartbeat(created.runner.id, created.key))
        assertEquals(clock.now, repository.runners.single().lastSeenAt)
        assertTrue(list(admin).single().online)

        clock.now += 90.seconds
        assertTrue(list(admin).single().online, "Still online at the edge of the window")
        clock.now += 1.seconds
        assertFalse(list(admin).single().online, "Offline once the window has passed")
    }

    @Test
    fun aHeartbeatIsRefusedTheSameForAnUnknownRunnerAWrongKeyOrARemovedOne() = runTest {
        val heartbeat = newHeartbeat()
        val created = register(admin, RunnerRegistration("build-1", emptyList(), null))
        val other = register(admin, RunnerRegistration("build-2", emptyList(), null))

        assertFailsWith<RunnerUnauthenticatedException> { heartbeat(Uuid.random(), created.key) }
        assertFailsWith<RunnerUnauthenticatedException> { heartbeat(created.runner.id, other.key) }
        assertFailsWith<RunnerUnauthenticatedException> { heartbeat(created.runner.id, "") }
        assertTrue(repository.runners.all { it.lastSeenAt == null }, "A refused heartbeat marks nothing")

        // Removed by an admin while its heartbeat is between the read and the write: refused, not answered 200.
        repository.beforeTouch = { repository.runners.removeAll { it.id == created.runner.id } }
        assertFailsWith<RunnerUnauthenticatedException> { heartbeat(created.runner.id, created.key) }
    }

    @Test
    fun aRunnersKeyIsNeverPrinted() {
        val key = RegisteredRunnerKey(ORG_A, "secret-hash")
        assertFalse("secret-hash" in key.toString())
        assertEquals(ORG_A, key.orgId)
    }

    @Test
    fun keysAreComparedWholeWhateverTheirLength() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertTrue(constantTimeEquals("", ""))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "ab"))
    }
}

/** The runners in memory, by organization then name; [outcome] forces what [register] answers. */
internal class InMemoryRegisteredRunners : RegisteredRunnersRepository {
    val runners = mutableListOf<RegisteredRunner>()
    var outcome = RunnerRegistrationOutcome.REGISTERED
    var lastMax = 0
    var lastLimit = 0

    override suspend fun register(runner: RegisteredRunner, max: Int): RunnerRegistrationOutcome {
        lastMax = max
        if (outcome == RunnerRegistrationOutcome.REGISTERED) runners += runner
        return outcome
    }

    override suspend fun list(orgId: Uuid, limit: Int): List<RegisteredRunner> {
        lastLimit = limit
        return runners.filter { it.orgId == orgId }.sortedBy { it.name.lowercase() }.take(limit)
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean =
        runners.removeAll { it.orgId == orgId && it.id == id }

    /** Runs between [keyOf] and [touch], as a removal racing a heartbeat would. */
    var beforeTouch: () -> Unit = {}

    override suspend fun keyOf(id: Uuid): RegisteredRunnerKey? =
        runners.firstOrNull { it.id == id }?.let { RegisteredRunnerKey(it.orgId, it.keyHash) }

    override suspend fun touch(orgId: Uuid, id: Uuid, keyHash: String, at: Instant, capacity: Int?): Boolean {
        beforeTouch()
        val i = runners.indexOfFirst { it.orgId == orgId && it.id == id && it.keyHash == keyHash }
        if (i >= 0) {
            runners[i] = runners[i].copy(lastSeenAt = maxOf(runners[i].lastSeenAt ?: at, at), capacity = capacity)
        }
        return i >= 0
    }
}
