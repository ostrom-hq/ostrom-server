// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin

import run.ostrom.server.admin.domain.AccountNotFoundException
import run.ostrom.server.admin.domain.AdminCaller
import run.ostrom.server.admin.domain.AdminSource
import run.ostrom.server.admin.domain.InstanceAdmin
import run.ostrom.server.admin.domain.InstanceAdminNotFoundException
import run.ostrom.server.admin.domain.InstanceAdminRequiredException
import run.ostrom.server.admin.domain.InstanceAdminsUseCaseImpl
import run.ostrom.server.admin.domain.LastInstanceAdminException
import run.ostrom.server.admin.domain.SeedInstanceAdminsUseCaseImpl
import run.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class InstanceAdminsTest {
    private val repository = MemoryInstanceAdmins()
    private val audit = RecordingAudit()
    private val logger = RecordingLogger()
    private val clock = SettableClock()

    private fun seed(accounts: FakeAccounts = FakeAccounts()) =
        SeedInstanceAdminsUseCaseImpl(repository, accounts, audit, logger, clock)

    private val admins = InstanceAdminsUseCaseImpl(repository, FakeAccounts(), audit, clock)

    @Test
    fun theSetupLinksPersonIsTheFirstAdministrator() = runTest {
        seed(FakeAccounts(owners = mapOf(ALICE to NOW)))(null)

        assertEquals(listOf(InstanceAdmin(ALICE, AdminSource.SETUP, NOW)), repository.list())
        assertTrue(seed().isAdmin(ALICE))
        assertFalse(seed().isAdmin(BOB))
        assertEquals(listOf("instance.admin.setup"), audit.records.map { it.event })
        assertNull(audit.records.single().actorId)
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun theFormerVariableIsImportedOnceAndSaidDeprecated() = runTest {
        // Carol has no account here: never an administrator nobody can be.
        seed()("$ALICE, not-an-id , ,$BOB,$CAROL")
        seed()("$ALICE")

        assertEquals(setOf(ALICE, BOB), repository.list().map { it.userId }.toSet())
        assertEquals(AdminSource.ENVIRONMENT, repository.list().first().source)
        assertEquals(listOf("instance.admin.imported", "instance.admin.imported"), audit.records.map { it.event })
        assertEquals(listOf(2, 0), logger.events.map { it.third["imported"] })
        assertEquals(listOf(2, 0), logger.events.map { it.third["ignored"] })
        assertTrue(logger.events.all { it.first == LogLevel.WARN && "deprecated" in it.second })
    }

    @Test
    fun aRemovedAdministratorIsNeverBroughtBackByTheSetupLinkOrTheVariable() = runTest {
        seed(FakeAccounts(owners = mapOf(ALICE to NOW)))("$BOB")
        admins.remove(AdminCaller(BOB, person = true), ALICE)

        seed(FakeAccounts(owners = mapOf(ALICE to NOW)))("$ALICE")
        seed().grantSetupOwner(ALICE, NOW)

        assertEquals(listOf(BOB), repository.list().map { it.userId })
    }

    @Test
    fun onlyAPersonWhoAdministersTheInstanceIsLetIn() = runTest {
        repository.addIfNeverSeen(InstanceAdmin(ALICE, AdminSource.SETUP, NOW))

        assertFailsWith<InstanceAdminRequiredException> { admins.list(AdminCaller(BOB, person = true)) }
        // Alice's own token, presented by a program, is not Alice at the console.
        assertFailsWith<InstanceAdminRequiredException> { admins.list(AdminCaller(ALICE, person = false)) }
        assertFailsWith<InstanceAdminRequiredException> { admins.add(AdminCaller(BOB, true), "bob@example.com") }
        assertFailsWith<InstanceAdminRequiredException> { admins.remove(AdminCaller(BOB, true), ALICE) }
        assertEquals("alice", admins.list(AdminCaller(ALICE, person = true)).single().account?.login)
    }

    @Test
    fun anAdministratorAddsAPersonByAVerifiedEmail() = runTest {
        repository.addIfNeverSeen(InstanceAdmin(ALICE, AdminSource.SETUP, NOW))
        val alice = AdminCaller(ALICE, person = true)
        clock.advance(1.minutes)

        val list = admins.add(alice, " bob@example.com ")
        admins.add(alice, "bob@example.com")

        assertEquals(listOf(ALICE, BOB), list.map { it.admin.userId })
        assertEquals(InstanceAdmin(BOB, AdminSource.DASHBOARD, NOW + 1.minutes, ALICE), list.last().admin)
        assertEquals(listOf("instance.admin.added"), audit.records.map { it.event })
        assertEquals(ALICE, audit.records.single().actorId)
        assertEquals(BOB.toString(), audit.records.single().subjectId)
        assertFailsWith<AccountNotFoundException> { admins.add(alice, "nobody@example.com") }
    }

    @Test
    fun theLastAdministratorStays() = runTest {
        repository.addIfNeverSeen(InstanceAdmin(ALICE, AdminSource.SETUP, NOW))
        repository.addIfNeverSeen(InstanceAdmin(BOB, AdminSource.SETUP, NOW + 1.minutes))
        val alice = AdminCaller(ALICE, person = true)

        assertFailsWith<InstanceAdminNotFoundException> { admins.remove(alice, CAROL) }
        admins.remove(alice, BOB)
        assertFailsWith<LastInstanceAdminException> { admins.remove(alice, ALICE) }
        assertEquals(listOf("instance.admin.removed"), audit.records.map { it.event })
        // Removed, Bob can be added back by an administrator's decision.
        admins.add(alice, "bob@example.com")
        assertTrue(repository.isAdmin(BOB))
    }

    @Test
    fun anAdministratorWhoseAccountIsGoneIsStillListed() = runTest {
        repository.addIfNeverSeen(InstanceAdmin(ALICE, AdminSource.SETUP, NOW))
        repository.addIfNeverSeen(InstanceAdmin(CAROL, AdminSource.ENVIRONMENT, NOW))

        val list = admins.list(AdminCaller(ALICE, person = true))

        assertNull(list.single { it.admin.userId == CAROL }.account)
    }
}
