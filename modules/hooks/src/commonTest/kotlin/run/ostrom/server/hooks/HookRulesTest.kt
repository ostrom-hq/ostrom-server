// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import run.ostrom.server.hooks.domain.EffectiveHook
import run.ostrom.server.hooks.domain.Hook
import run.ostrom.server.hooks.domain.HookArtifacts
import run.ostrom.server.hooks.domain.HookCandidate
import run.ostrom.server.hooks.domain.HookDefinition
import run.ostrom.server.hooks.domain.HookDownload
import run.ostrom.server.hooks.domain.HookEvent
import run.ostrom.server.hooks.domain.HookVersion
import run.ostrom.server.hooks.domain.effective
import run.ostrom.server.hooks.fakes.ADA
import run.ostrom.server.hooks.fakes.ORG_A
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileEntry
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.infrastructure.JdkSha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class HookRulesTest {
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val definition = HookDefinition(setOf(HookEvent.STOP, HookEvent.PRE_TOOL_USE), "Bash", "run.sh", 60)
    private val hook = Hook(Uuid.random(), ORG_A, "guard", null, null, false, t0, t0)
    private val version = HookVersion(Uuid.random(), hook.id, "1.0.0", "sha256:d", definition, 10, 1, "$ADA", t0)

    @Test
    fun eventsReadTheirWireNamesSorted() {
        assertEquals(HookEvent.SUBAGENT_STOP, HookEvent.fromWire("SubagentStop"))
        assertNull(HookEvent.fromWire("stop"), "Case-sensitive, as the agents name them")
        assertEquals(listOf("PreToolUse", "Stop"), definition.wireEvents)
        assertEquals(9, HookEvent.entries.size)
    }

    @Test
    fun aMemberReceivesOnlyAPinnedAllowedVersion() {
        val pinned = hook.copy(pinnedVersionId = version.id, pinnedVersion = "1.0.0", allMembers = true)
        assertEquals(EffectiveHook("guard", version), HookCandidate(pinned, version, null).effective())
        assertNull(HookCandidate(hook, null, Decision.ALLOW).effective(), "Nothing pinned, nothing delivered")
        assertNull(HookCandidate(pinned, version, Decision.DENY).effective(), "An explicit no wins")
        assertNull(HookCandidate(pinned.copy(allMembers = false), version, null).effective())
        assertEquals("guard", HookCandidate(pinned.copy(allMembers = false), version, Decision.ALLOW).effective()?.name)
    }

    @Test
    fun theDigestCoversTheDefinitionAsTeamCodeComputesIt() = runTest {
        val sha256 = JdkSha256()
        val content = "#!/bin/sh\n".encodeToByteArray()
        val entry = FileEntry("run.sh", FileMode.EXECUTABLE, content.size, sha256.hex(content))
        assertEquals(
            "sha256:352fbef20561d4d659e0a1292a139d5fbcde4328247bd6c05bbf930799de1798",
            HookArtifacts.digest(definition, listOf(entry), sha256),
        )
        val other = HookArtifacts.digest(definition.copy(timeoutSeconds = 61), listOf(entry), sha256)
        assertFalse(other.endsWith("352fbef20561d4d659e0a1292a139d5fbcde4328247bd6c05bbf930799de1798"))
    }

    @Test
    fun aDownloadNeverPrintsItsContent() {
        val file = ArtifactFile("run.sh", FileMode.EXECUTABLE, "secret".encodeToByteArray())
        val entry = FileEntry("run.sh", FileMode.EXECUTABLE, 6, "e")
        val download = HookDownload("guard", version, listOf(entry), listOf(file))
        assertEquals("HookDownload(name=guard, version=1.0.0, files=1)", download.toString())
    }
}
