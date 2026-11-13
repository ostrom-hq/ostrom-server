// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.skills.domain.AccessPolicy
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.Candidate
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CredentialKind
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.EffectiveSkill
import run.ostrom.server.skills.domain.FileEntry
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.InstallationStatus
import run.ostrom.server.skills.domain.Skill
import run.ostrom.server.skills.domain.SkillDownload
import run.ostrom.server.skills.domain.SkillVersion
import run.ostrom.server.skills.domain.ValidatedArtifact
import run.ostrom.server.skills.domain.effectiveFor
import run.ostrom.server.skills.fakes.ADA
import run.ostrom.server.skills.fakes.BEN
import run.ostrom.server.skills.fakes.ORG_A
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CatalogRulesTest {
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val skill = Skill(Uuid.random(), ORG_A, "review", null, null, null, false, null, t0, t0)
    private val version = SkillVersion(Uuid.random(), skill.id, "1.0.0", "sha256:d", 7, 1, "$ADA", t0, null)

    private fun caller(credential: CredentialKind, vararg scopes: String) = CatalogCaller(
        ORG_A, "$ADA", false, credential, scopes.toSet(), null, "cred-1",
        AuthenticatedPrincipal("$ADA", PrincipalKind.USER, scopes.toSet()),
    )

    @Test
    fun anExplicitDecisionWinsAndAllMembersIsTheWeakestRule() {
        assertTrue(AccessPolicy.allowed(null, allMembers = true))
        assertFalse(AccessPolicy.allowed(null, allMembers = false))
        assertTrue(AccessPolicy.allowed(Decision.ALLOW, allMembers = false))
        assertFalse(AccessPolicy.allowed(Decision.DENY, allMembers = true))
    }

    @Test
    fun aMemberReceivesOnlyAnActivePinnedAllowedVersion() {
        val pinned = skill.copy(pinnedVersionId = version.id, pinnedVersion = "1.0.0", allMembers = true)
        val expected = EffectiveSkill("review", version.id, "1.0.0", "sha256:d", 7, 1)
        assertEquals(expected, Candidate(pinned, version, null).effectiveFor("$ADA"))
        assertNull(Candidate(skill, null, Decision.ALLOW).effectiveFor("$ADA"), "Nothing pinned, nothing delivered")
        assertNull(Candidate(pinned, version, Decision.DENY).effectiveFor("$ADA"))
        assertNull(Candidate(pinned.copy(retiredAt = t0), version, null).effectiveFor("$ADA"))
        assertNull(Candidate(pinned, version.copy(retiredAt = t0), null).effectiveFor("$ADA"))
        // A personal skill reaches its owner whatever the audience, and nobody else.
        val personal = pinned.copy(ownerId = "$ADA", allMembers = false)
        assertEquals(expected, Candidate(personal, version, null).effectiveFor("$ADA"))
        assertNull(Candidate(personal, version, Decision.ALLOW).effectiveFor("$BEN"))
    }

    @Test
    fun aCallersScopesFollowItsCredential() {
        assertTrue(caller(CredentialKind.CONSOLE).allows("skills:write"))
        assertTrue(caller(CredentialKind.PERSON_TOKEN, "skills:write").allows("skills:write"))
        assertTrue(caller(CredentialKind.PERSON_TOKEN, "user:*").allows("skills:write"))
        assertFalse(caller(CredentialKind.PERSON_TOKEN, "skills:read").allows("skills:write"))
        assertFalse(caller(CredentialKind.SERVICE_KEY, "user:*").allows("skills:read"), "A key lists its scopes")
        assertEquals("cred-1", caller(CredentialKind.SERVICE_KEY).keyId)
        assertNull(caller(CredentialKind.PERSON_TOKEN).keyId)
    }

    @Test
    fun wireValuesAndLogLinesNeverCarryContent() {
        assertEquals(Decision.DENY, Decision.fromWire("deny"))
        assertNull(Decision.fromWire("maybe"))
        assertEquals(InstallationStatus.CONFLICT, InstallationStatus.fromWire("conflict"))
        assertNull(InstallationStatus.fromWire("done"))
        val file = ArtifactFile("SKILL.md", FileMode.FILE, "secret".encodeToByteArray())
        assertEquals("ArtifactFile(path=SKILL.md, mode=FILE, size=6)", file.toString())
        val entry = FileEntry("SKILL.md", FileMode.FILE, 6, "e")
        val artifact = ValidatedArtifact("sha256:d", listOf(file), listOf(entry))
        assertEquals("ValidatedArtifact(digest=sha256:d, files=1)", artifact.toString())
        assertEquals(6L, artifact.sizeBytes)
        val effective = EffectiveSkill("review", version.id, "1.0.0", "sha256:d", 6, 1)
        val download = SkillDownload(effective, listOf(entry), listOf(file))
        assertFalse("secret" in download.toString())
    }
}
