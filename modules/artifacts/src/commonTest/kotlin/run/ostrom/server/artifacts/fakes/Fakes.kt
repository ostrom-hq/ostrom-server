// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.fakes

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.ViewTicket
import run.ostrom.server.artifacts.domain.ViewerChecks
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
val ALICE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
val BOB: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000b0b")
val DAVE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000da7e")
val CAROL: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ca01")

val alice = ArtifactCaller(ORG_A, ALICE.toString(), person = true, "alice-session", "alice")
val bob = ArtifactCaller(ORG_A, BOB.toString(), person = true, "bob-session", "bob")
val dave = ArtifactCaller(ORG_A, DAVE.toString(), person = true, "dave-session", "dave")
val carol = ArtifactCaller(ORG_B, CAROL.toString(), person = true, "carol-session", "carol")

/** Alice through a token a program presents (her agent's): never a person. */
val aliceAgent = ArtifactCaller(ORG_A, ALICE.toString(), person = false, "alice-token", "alice")

/** The members of the tests' organizations. */
private val MEMBERS = mapOf(ORG_A to setOf(ALICE, BOB, DAVE), ORG_B to setOf(CAROL)).mapValues { (_, ids) ->
    ids.map { it.toString() }.toSet()
}

/**
 * [ViewerChecks] the test drives: every member of [MEMBERS] is signed in with every credential, except the
 * credentials in [signedOut] and the members in [removed].
 */
class TestViewers : ViewerChecks {
    val signedOut = mutableSetOf<String>()
    val removed = mutableSetOf<String>()
    var checks = 0

    override suspend fun stillSignedIn(orgId: Uuid, ticket: ViewTicket): Boolean {
        checks++
        return ticket.memberId in MEMBERS[orgId].orEmpty() && ticket.memberId !in removed &&
            ticket.credentialId !in signedOut
    }
}

/** The content origin and viewer of the tests. */
const val CONTENT_ORIGIN = "http://artifacts.test"
const val VIEWER = "https://app.test/artifacts"

/**
 * The test identities: `alice`, `bob` (members of org A), `dave` (admin of org A), `carol` (owner of org B) and
 * `nobody` (no org) are browser sessions; `reader` and `writer` are alice's service tokens with `artifacts:read`
 * and `artifacts:write`; `cloud` is alice's with no artifact scope; `agent` and `dave-agent` are personal tokens
 * with `artifacts:write` that alice and dave hand to their agents.
 */
val testIdentities = module {
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            // The browser sessions of the test identities stay live; tokens never open pages.
            override suspend fun isLive(subjectId: String, credentialId: String): Boolean =
                credentialId == "$subjectId-session"

            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "alice", "bob", "dave", "carol", "nobody" -> AuthenticatedPrincipal(
                    token,
                    PrincipalKind.USER,
                    setOf("user:*"),
                    credentialId = "$token-session",
                    channel = PrincipalChannel.BROWSER,
                )
                "agent" -> AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("artifacts:write"), "alice-token")
                "dave-agent" ->
                    AuthenticatedPrincipal("dave", PrincipalKind.USER, setOf("artifacts:write"), "dave-token")
                "reader" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("artifacts:read"))
                "writer" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("artifacts:write"))
                "cloud" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("cloud:read"))
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> { TestOrgResolver }
    single<MemberDirectory> {
        object : MemberDirectory {
            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                memberId in MEMBERS[orgId].orEmpty()
        }
    }
    single<ConfigSource> {
        MapConfigSource(
            mapOf("OSTROM_ARTIFACTS_CONTENT_ORIGIN" to CONTENT_ORIGIN, "OSTROM_ARTIFACTS_VIEWER_URL" to VIEWER),
        )
    }
}

/** The test identities' organizations. */
object TestOrgResolver : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
        when (principal.subjectId) {
            "alice" -> OrgPrincipal(ORG_A, ALICE, OrgRole.MEMBER)
            "bob" -> OrgPrincipal(ORG_A, BOB, OrgRole.MEMBER)
            "dave" -> OrgPrincipal(ORG_A, DAVE, OrgRole.ADMIN)
            "carol" -> OrgPrincipal(ORG_B, CAROL, OrgRole.OWNER)
            else -> null
        }
}

/** A clock the test moves. */
class TestClock(var now: Instant = Instant.parse("2026-10-30T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** [ArtifactsRepository] in memory, with a switch to lose the race of the next version. */
class InMemoryArtifacts : ArtifactsRepository {
    val artifacts = mutableMapOf<Uuid, Artifact>()
    val versions = mutableMapOf<Pair<Uuid, Int>, ArtifactVersion>()
    val blobs = mutableMapOf<Pair<Uuid, String>, ByteArray>()
    val shares = mutableMapOf<Uuid, MutableSet<String>>()
    var loseNextRace = false

    override suspend fun create(artifact: Artifact, version: ArtifactVersion, blobs: Map<String, ByteArray>) {
        artifacts[artifact.id] = artifact
        store(version, blobs)
    }

    override suspend fun addVersion(
        orgId: Uuid,
        version: ArtifactVersion,
        blobs: Map<String, ByteArray>,
        now: Instant,
    ): Artifact? {
        if (loseNextRace) {
            loseNextRace = false
            return null
        }
        val artifact = artifacts.getValue(version.artifactId).copy(latestVersion = version.number, updatedAt = now)
        artifacts[artifact.id] = artifact
        store(version, blobs)
        return artifact
    }

    private fun store(version: ArtifactVersion, blobs: Map<String, ByteArray>) {
        versions[version.artifactId to version.number] = version
        blobs.forEach { (sha, bytes) -> this.blobs[version.artifactId to sha] = bytes }
    }

    override suspend fun updateSettings(artifact: Artifact) {
        artifacts[artifact.id] = artifact
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Artifact? = artifacts[id]?.takeIf { it.orgId == orgId }

    override suspend fun getById(id: Uuid): Artifact? = artifacts[id]

    override suspend fun getByLinkSlug(slug: String): Artifact? = artifacts.values.firstOrNull { it.linkSlug == slug }

    // After `before`, the ones that follow it; none after an artifact it does not hold, as the database.
    override suspend fun listReadable(caller: ArtifactCaller, before: Uuid?, limit: Int): List<Artifact> {
        val all = artifacts.values.filter { it.orgId == caller.orgId }
        val at = if (before == null) -1 else all.indexOfFirst { it.id == before }
        return if (before != null && at < 0) emptyList() else all.drop(at + 1).take(limit)
    }

    override suspend fun versions(orgId: Uuid, artifactId: Uuid): List<ArtifactVersion> =
        versions.values.filter { it.artifactId == artifactId }.sortedByDescending { it.number }

    override suspend fun version(orgId: Uuid, artifactId: Uuid, number: Int): ArtifactVersion? =
        versions[artifactId to number]

    override suspend fun blob(orgId: Uuid, artifactId: Uuid, sha256: String): ByteArray? = blobs[artifactId to sha256]

    override suspend fun blobDigests(orgId: Uuid, artifactId: Uuid): Set<String> =
        blobs.keys.filter { it.first == artifactId }.map { it.second }.toSet()

    override suspend fun members(orgId: Uuid, artifactId: Uuid): List<String> = shares[artifactId].orEmpty().sorted()

    override suspend fun setMember(orgId: Uuid, artifactId: Uuid, memberId: String, share: Boolean) {
        val set = shares.getOrPut(artifactId) { mutableSetOf() }
        if (share) set += memberId else set -= memberId
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean {
        versions.keys.removeAll { it.first == id }
        blobs.keys.removeAll { it.first == id }
        return artifacts.remove(id) != null
    }
}
