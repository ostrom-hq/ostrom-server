// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.server.compiler.domain.repositories.WorkflowSettingsRepository
import run.ostrom.server.compiler.domain.usecases.WorkflowSettingsUseCaseImpl
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WorkflowSettingsUseCaseTest {
    private val org = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val ada = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val bob = Uuid.parse("00000000-0000-0000-0000-0000000000a2")
    private val stored = mutableMapOf<Uuid, Pair<WorkflowEditors, String>>()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val principal = AuthenticatedPrincipal("user:lead", PrincipalKind.USER, emptySet())
    private val logins = mapOf("ada" to ada, "bob" to bob)

    private val settings = WorkflowSettingsUseCaseImpl(
        object : WorkflowSettingsRepository {
            override suspend fun editors(orgId: Uuid): WorkflowEditors? = stored[orgId]?.first

            override suspend fun saveEditors(orgId: Uuid, editors: WorkflowEditors, by: String, at: Instant) {
                stored[orgId] = editors to by
            }
        },
        object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String) = memberId in logins.values.map { "$it" }
        },
        object : MemberLogins {
            override suspend fun idsOf(orgId: Uuid, logins: Collection<String>) =
                this@WorkflowSettingsUseCaseTest.logins.filterKeys { it in logins }

            override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>) =
                logins.entries.filter { it.value in memberIds }.associate { it.value to it.key }
        },
        OrgAuditTrail { audit += it },
        SettableClock(),
    )

    private suspend fun refused(block: suspend () -> Unit): OstromException = assertFailsWith { block() }

    @Test
    fun withoutARuleOnlyOwnersAndAdminsDecide() = runTest {
        assertEquals("admins", settings.settings(org, "ada", OrgRole.MEMBER).editors.mode)
        assertFalse(settings.settings(org, "ada", OrgRole.MEMBER).canEdit)
        assertTrue(settings.settings(org, "ada", OrgRole.ADMIN).canEdit)
        assertTrue(settings.mayDecide(org, "lead", OrgRole.OWNER, gated = true))
        assertFalse(settings.mayDecide(org, "ada", OrgRole.MEMBER, gated = false))
        assertFalse(settings.mayDecide(org, "agent", null, gated = false))
    }

    @Test
    fun theRuleAllLetsEveryMemberInButNeverOnAGate() = runTest {
        settings.save(org, "lead", OrgRole.OWNER, principal, WorkflowEditors("all"))
        assertTrue(settings.mayDecide(org, "ada", OrgRole.MEMBER, gated = false))
        assertFalse(settings.mayDecide(org, "ada", OrgRole.MEMBER, gated = true))
        assertFalse(settings.mayDecide(org, "agent", null, gated = false))
        assertTrue(settings.settings(org, "ada", OrgRole.APPROVER).canEdit)
    }

    @Test
    fun theRuleMembersLetsTheListedOnesIn() = runTest {
        val list = WorkflowEditors("members", listOf("$ada", "$ada"))
        val saved = settings.save(org, "lead", OrgRole.ADMIN, principal, list)
        assertEquals(listOf("$ada"), saved.editors.memberIds)
        assertTrue(saved.canEdit)
        assertTrue(settings.mayDecide(org, "ada", OrgRole.MEMBER, gated = false))
        assertFalse(settings.mayDecide(org, "bob", OrgRole.MEMBER, gated = false))
        assertFalse(settings.mayDecide(org, "ghost", OrgRole.MEMBER, gated = false))
        assertEquals("lead", stored.getValue(org).second)
        val record = audit.single()
        assertEquals("workflow.editors.changed", record.event)
        assertEquals("members", record.details["mode"])
        assertEquals("1", record.details["editors"])
        // Changing back to admins forgets the list.
        assertNull(settings.save(org, "lead", OrgRole.ADMIN, principal, WorkflowEditors("admins")).editors.memberIds)
    }

    @Test
    fun onlyAnOwnerOrAnAdminChangesTheRule() = runTest {
        for (role in listOf(OrgRole.MEMBER, OrgRole.APPROVER, null)) {
            val error = refused { settings.save(org, "ada", role, principal, WorkflowEditors("all")) }
            assertEquals(ErrorKind.FORBIDDEN, error.kind)
            assertEquals("workflow_editors_only", error.type)
        }
        assertTrue(audit.isEmpty())
    }

    @Test
    fun aBadRuleIsRefused() = runTest {
        val tooMany = List(WorkflowSettingsUseCaseImpl.MAX_EDITORS + 1) { "${Uuid.random()}" }
        for (editors in listOf(
            WorkflowEditors("everyone"),
            WorkflowEditors("all", listOf("$ada")),
            WorkflowEditors("members", tooMany),
            WorkflowEditors("members", listOf("${Uuid.random()}")),
        )) {
            val error = refused { settings.save(org, "lead", OrgRole.OWNER, principal, editors) }
            assertEquals(ErrorKind.INVALID_REQUEST, error.kind)
            assertEquals("invalid_editors", error.type)
        }
        assertTrue(stored.isEmpty())
    }
}
