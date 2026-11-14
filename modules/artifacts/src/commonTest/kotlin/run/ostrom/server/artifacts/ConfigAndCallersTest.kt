// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.ArtifactOrigin
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.MissingScopeException
import run.ostrom.server.artifacts.domain.NotAMemberException
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.DAVE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.TestOrgResolver
import run.ostrom.server.artifacts.infrastructure.ArtifactsConfig
import run.ostrom.server.artifacts.presentation.ARTIFACTS_READ
import run.ostrom.server.artifacts.presentation.ARTIFACTS_WRITE
import run.ostrom.server.artifacts.presentation.ArtifactOrgResolver
import run.ostrom.server.artifacts.presentation.FileDto
import run.ostrom.server.artifacts.presentation.artifactCallerOf
import run.ostrom.server.artifacts.presentation.contentHeaders
import run.ostrom.server.artifacts.presentation.parseTicketRequest
import run.ostrom.server.artifacts.presentation.toUploads
import run.ostrom.server.kernel.auth.AgentMember
import run.ostrom.server.kernel.auth.AgentMembers
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel.BROWSER
import run.ostrom.server.kernel.auth.PrincipalChannel.MOBILE_APP
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ConfigAndCallersTest {
    private fun config(vararg values: Pair<String, String>) = ArtifactsConfig.from(MapConfigSource(values.toMap()))

    @Test
    fun locationsAreReadAndTheContentOriginStandsApart() {
        assertEquals(ArtifactLocations("/artifacts", null), config())
        assertEquals(
            ArtifactLocations("https://app.example.com/artifacts", "https://artifacts.example.com:8443"),
            config(
                "OSTROM_ARTIFACTS_VIEWER_URL" to "https://app.example.com/artifacts/",
                "OSTROM_ARTIFACTS_CONTENT_ORIGIN" to "https://artifacts.example.com:8443/",
            ),
        )
        assertEquals("/artifacts", config("OSTROM_ARTIFACTS_VIEWER_URL" to " ").viewerUrl)
        assertNull(config("OSTROM_ARTIFACTS_CONTENT_ORIGIN" to " ").contentOrigin)
        listOf("ftp://x.io", "https://x.io/path", "artifacts.example.com").forEach {
            assertFailsWith<InvalidConfigException>(it) { config("OSTROM_ARTIFACTS_CONTENT_ORIGIN" to it) }
        }
        assertFailsWith<InvalidConfigException> {
            config(
                "OSTROM_ARTIFACTS_VIEWER_URL" to "https://x.io/artifacts",
                "OSTROM_ARTIFACTS_CONTENT_ORIGIN" to "https://x.io",
            )
        }
        assertEquals(
            "artifacts.example.com",
            ArtifactsConfig.contentHost(ArtifactLocations("/a", "https://artifacts.example.com:8443")),
        )
        assertNull(ArtifactsConfig.contentHost(ArtifactLocations("/a", null)))
    }

    @Test
    fun onlyTheConsoleFramesPagesWhenItsAddressIsKnown() {
        val absolute = contentHeaders(ArtifactLocations("https://app.example.com/artifacts", "https://a.example.com"))
        assertTrue("frame-ancestors https://app.example.com;" in absolute.getValue("Content-Security-Policy") + ";")
        val relative = contentHeaders(ArtifactLocations("/artifacts", "https://a.example.com"))
        assertTrue("frame-ancestors *" in relative.getValue("Content-Security-Policy"))
        val plain = contentHeaders(ArtifactLocations("http://localhost:3000/artifacts", "https://a.example.com"))
        assertTrue("frame-ancestors http://localhost:3000" in plain.getValue("Content-Security-Policy"))
        assertTrue("sandbox allow-scripts" in plain.getValue("Content-Security-Policy"))
        assertEquals("nosniff", plain["X-Content-Type-Options"])
    }

    @Test
    fun theModuleReadsTheBoundConfiguration() {
        val bound = module { single<ConfigSource> { MapConfigSource(mapOf("OSTROM_ARTIFACTS_VIEWER_URL" to "/v")) } }
        val koin = koinApplication { modules(bound, ArtifactsModule.koinModule) }
        assertEquals("/v", koin.koin.get<ArtifactLocations>().viewerUrl)
        koin.close()
    }

    @Test
    fun scopesAndMembershipMakeTheCaller() = runTest {
        fun principal(
            kind: PrincipalKind,
            vararg scopes: String,
        ) = AuthenticatedPrincipal("alice", kind, scopes.toSet())
        // A person's or a program's credential is the kernel's resolver's.
        val kernel = ArtifactOrgResolver(TestOrgResolver, null)
        val caller = kernel.artifactCallerOf(principal(PrincipalKind.SERVICE, ARTIFACTS_WRITE), ARTIFACTS_READ)
        assertEquals(ORG_A, caller.orgId)
        assertEquals(ALICE.toString(), caller.memberId)
        assertFalse(caller.person, "A token a program presents is no person")
        assertFalse(caller.administrator)
        // Owners and administrators publish when only they may.
        for (who in listOf("dave", "carol")) {
            val person = AuthenticatedPrincipal(who, PrincipalKind.USER, setOf("user:*"))
            assertTrue(kernel.artifactCallerOf(person, ARTIFACTS_WRITE).administrator)
        }
        kernel.artifactCallerOf(principal(PrincipalKind.SERVICE, ARTIFACTS_READ), ARTIFACTS_READ)
        kernel.artifactCallerOf(principal(PrincipalKind.USER, "user:*"), ARTIFACTS_WRITE)
        assertFailsWith<MissingScopeException> {
            kernel.artifactCallerOf(principal(PrincipalKind.SERVICE, ARTIFACTS_READ), ARTIFACTS_WRITE)
        }
        assertFailsWith<MissingScopeException> {
            kernel.artifactCallerOf(principal(PrincipalKind.SERVICE, "user:*"), ARTIFACTS_READ)
        }
        assertFailsWith<NotAMemberException> {
            kernel.artifactCallerOf(
                AuthenticatedPrincipal("nobody", PrincipalKind.USER, setOf("user:*")),
                ARTIFACTS_READ,
            )
        }
        val browser = kernel.artifactCallerOf(
            AuthenticatedPrincipal("dave", PrincipalKind.USER, setOf("user:*"), "s1", channel = BROWSER),
            ARTIFACTS_READ,
        )
        assertTrue(browser.person)
        assertEquals("s1", browser.credentialId)
        val app = kernel.artifactCallerOf(
            AuthenticatedPrincipal("carol", PrincipalKind.USER, setOf(ARTIFACTS_READ), channel = MOBILE_APP),
            ARTIFACTS_READ,
        )
        assertTrue(app.person)
        val personalToken = kernel.artifactCallerOf(
            AuthenticatedPrincipal("dave", PrincipalKind.USER, setOf(ARTIFACTS_WRITE), "t1"),
            ARTIFACTS_WRITE,
        )
        assertFalse(personalToken.person, "A personal token, an administrator's included, is no person")
        val cli = kernel.artifactCallerOf(
            AuthenticatedPrincipal("dave", PrincipalKind.USER, setOf(ARTIFACTS_WRITE), "c1", signedIn = true),
            ARTIFACTS_WRITE,
        )
        assertTrue(cli.person, "The CLI's sign-in is a person, as TeamCode's interactive login")
    }

    @Test
    fun anAgentTokenIsTheMemberWhoLaunchedItAndNeverAPerson() = runTest {
        // TC-ART-14, 38: the launches module's reading of an agent token (the kernel's AgentMembers).
        val launches = object : AgentMembers {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): AgentMember? {
                val role = if (principal.subjectId == "launch-1") OrgRole.ADMIN else OrgRole.MEMBER
                val session = "session-1".takeIf { principal.subjectId == "launch-1" }
                val launched = AgentMember(OrgPrincipal(ORG_A, DAVE, role), principal.subjectId, session)
                return launched.takeIf { principal.subjectId != "ended" }
            }
        }
        val resolver = ArtifactOrgResolver(TestOrgResolver, launches)
        val scopes = setOf("agent:session", ARTIFACTS_WRITE)
        val agent = AuthenticatedPrincipal("launch-1", PrincipalKind.AGENT, scopes, "a1")
        val caller = resolver.artifactCallerOf(agent, ARTIFACTS_WRITE)
        assertEquals(ORG_A, caller.orgId)
        assertEquals(DAVE.toString(), caller.memberId)
        assertTrue(caller.administrator, "An administrator's agent publishes where only administrators do")
        assertFalse(caller.person, "An agent never makes an artifact public nor deletes one")
        assertEquals("a1", caller.credentialId)
        // TC-ART-39: its launch and cloud session are the origin of what it publishes; a machine launch has no session.
        assertEquals(ArtifactOrigin("launch-1", "session-1"), caller.origin)
        val machine = resolver.artifactCallerOf(agent.copy(subjectId = "launch-2"), ARTIFACTS_READ)
        assertEquals(ArtifactOrigin("launch-2"), machine.origin)
        assertFalse(resolver.artifactCallerOf(agent.copy(subjectId = "launch-2"), ARTIFACTS_READ).administrator)
        // A person's credential is still the kernel's resolver's.
        val person = AuthenticatedPrincipal("dave", PrincipalKind.USER, setOf("user:*"))
        assertTrue(resolver.artifactCallerOf(person, ARTIFACTS_WRITE).administrator)
        assertNull(resolver.artifactCallerOf(person, ARTIFACTS_WRITE).origin, "A person's publication has no origin")
        // An ended launch, a token issued before the scope, or a server without the launches module: refused.
        assertFailsWith<NotAMemberException> {
            resolver.artifactCallerOf(agent.copy(subjectId = "ended"), ARTIFACTS_READ)
        }
        assertFailsWith<MissingScopeException> {
            resolver.artifactCallerOf(agent.copy(scopes = setOf("agent:session")), ARTIFACTS_READ)
        }
        assertFailsWith<NotAMemberException> {
            ArtifactOrgResolver(TestOrgResolver, null).artifactCallerOf(agent, ARTIFACTS_WRITE)
        }
        // The resolver itself: an agent token as its launch's member, a person as the kernel's.
        assertEquals(DAVE, resolver.resolve(agent, null)?.memberId)
        assertNull(resolver.resolve(agent.copy(subjectId = "ended"), null))
        assertNull(ArtifactOrgResolver(TestOrgResolver, null).resolve(agent, null))
        assertEquals(TestOrgResolver.resolve(person, null), resolver.resolve(person, null))
        assertEquals(TestOrgResolver.profile(ORG_A), resolver.profile(ORG_A), "The organization is the kernel's")
    }

    @Test
    fun filesDecodeFromTextOrBase64() {
        val files = listOf(
            FileDto("a.txt", "héllo"),
            FileDto("b.txt", "hi", "utf8"),
            FileDto("c.bin", "AQID", "base64"),
        ).toUploads()
        assertEquals("héllo", files[0].bytes.decodeToString())
        assertContentEquals(byteArrayOf(1, 2, 3), files[2].bytes)
        assertFailsWith<InvalidArtifactRequestException> { listOf(FileDto("c.bin", "%%%", "base64")).toUploads() }
        assertFailsWith<InvalidArtifactRequestException> { listOf(FileDto("c.bin", "x", "hex")).toUploads() }
    }

    @Test
    fun aTicketRequestMayBeEmpty() {
        assertNull(parseTicketRequest("").version)
        assertEquals(3, parseTicketRequest("""{"version":3}""").version)
        assertFailsWith<InvalidArtifactRequestException> { parseTicketRequest("{nope") }
    }
}
