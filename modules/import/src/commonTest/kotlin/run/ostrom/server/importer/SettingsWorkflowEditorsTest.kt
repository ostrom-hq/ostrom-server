// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.core.api.models.WorkflowSettings
import run.ostrom.server.compiler.domain.usecases.WorkflowSettingsUseCase
import run.ostrom.server.importer.domain.EditorsChange
import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.SettingsWorkflowEditors
import run.ostrom.server.importer.domain.workflowEditorsOf
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SettingsWorkflowEditorsTest {
    private val principal = AuthenticatedPrincipal("user-nina", PrincipalKind.USER, setOf("import:write"))
    private val lena = Importing.memberA.memberId
    private val vera = Importing.otherA.memberId

    private class Settings(var editors: WorkflowEditors = WorkflowEditors("admins")) : WorkflowSettingsUseCase {
        val saves = mutableListOf<Triple<String, OrgRole?, WorkflowEditors>>()
        val readers = mutableListOf<OrgRole?>()

        override suspend fun settings(orgId: Uuid, login: String, role: OrgRole?): WorkflowSettings {
            readers += role
            return WorkflowSettings(editors, canEdit = true)
        }

        override suspend fun save(
            orgId: Uuid,
            login: String,
            role: OrgRole?,
            principal: AuthenticatedPrincipal,
            editors: WorkflowEditors,
        ): WorkflowSettings {
            saves += Triple(login, role, editors)
            this.editors = editors
            return WorkflowSettings(editors, canEdit = true)
        }

        override suspend fun mayDecide(orgId: Uuid, login: String, role: OrgRole?, gated: Boolean): Boolean =
            error("Unused.")
    }

    @Test
    fun theRuleIsSavedAsTheConsoleSavesItOnlyWhenItDiffers() = runTest {
        val settings = Settings()
        val editors = SettingsWorkflowEditors(settings)
        val members = EditorsChange("members", listOf(lena, vera, lena))

        assertEquals(ImportAction.PLANNED, editors.import(Importing.adminA, "nina", principal, members, dryRun = true))
        assertEquals(emptyList(), settings.saves)
        assertEquals(ImportAction.UPDATED, editors.import(Importing.ownerA, "nina", principal, members, dryRun = false))
        assertEquals(
            Triple("nina", OrgRole.OWNER, WorkflowEditors("members", listOf("$lena", "$vera"))),
            settings.saves.single(),
        )
        val reordered = EditorsChange("members", listOf(vera, lena))
        assertEquals(ImportAction.UNCHANGED, editors.import(Importing.adminA, "nina", principal, reordered, false))
        val fewer = EditorsChange("members", listOf(vera))
        assertEquals(ImportAction.UPDATED, editors.import(Importing.adminA, "nina", principal, fewer, false))

        val all = EditorsChange("all", emptyList())
        assertEquals(ImportAction.UPDATED, editors.import(Importing.adminA, "nina", principal, all, false))
        assertEquals(WorkflowEditors("all", null), settings.saves.last().third)
        assertEquals(ImportAction.UNCHANGED, editors.import(Importing.adminA, "nina", principal, all, false))
        assertEquals(listOf(OrgRole.ADMIN, OrgRole.OWNER, OrgRole.ADMIN), settings.readers.take(3))
    }

    @Test
    fun theImportSetsTheRuleOnlyWhereTheCompilerKeepsIt() {
        assertNull(workflowEditorsOf(null))
        assertNotNull(workflowEditorsOf(Settings()))
    }
}
