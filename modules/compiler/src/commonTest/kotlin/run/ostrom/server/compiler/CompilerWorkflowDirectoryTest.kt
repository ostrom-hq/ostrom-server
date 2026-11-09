// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.infrastructure.CompilerWorkflowDirectory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CompilerWorkflowDirectoryTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")

    @Test
    fun aWorkflowIsNamedInItsOwnOrganizationOnly() = runTest {
        val repository = MemoryCompilerRepository()
        val review = workflow("review", listOf(node("t", WorkflowNodeKind.TRIGGER)), name = "Weekly review")
        val raw = PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), review).jsonObject
        repository.saveWorkflow(orgA, "review", raw, null, Authorship("lead", NOW))
        val directory = CompilerWorkflowDirectory(repository)
        assertEquals("Weekly review", directory.name(orgA, "review"))
        assertNull(directory.name(orgB, "review"), "Another organization's workflow is none of this one's")
        assertNull(directory.name(orgA, "release"))
    }
}
