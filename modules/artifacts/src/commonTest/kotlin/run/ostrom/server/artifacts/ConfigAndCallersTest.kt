// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.MissingScopeException
import run.ostrom.server.artifacts.domain.NotAMemberException
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.TestOrgResolver
import run.ostrom.server.artifacts.infrastructure.ArtifactsConfig
import run.ostrom.server.artifacts.presentation.ARTIFACTS_MANAGE
import run.ostrom.server.artifacts.presentation.ARTIFACTS_READ
import run.ostrom.server.artifacts.presentation.ARTIFACTS_WRITE
import run.ostrom.server.artifacts.presentation.FileDto
import run.ostrom.server.artifacts.presentation.artifactCallerOf
import run.ostrom.server.artifacts.presentation.contentHeaders
import run.ostrom.server.artifacts.presentation.parseTicketRequest
import run.ostrom.server.artifacts.presentation.toUploads
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
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
    fun aServiceKeyNeverAdministersNorChangesAnAccessLevelOrDeletes() = runTest {
        val dave = "dave"
        val key = AuthenticatedPrincipal(dave, PrincipalKind.SERVICE, setOf(ARTIFACTS_WRITE))
        // Its creator is an administrator: the key reads and writes as a member, never as the administrator.
        assertFalse(TestOrgResolver.artifactCallerOf(key, ARTIFACTS_WRITE).admin)
        assertFailsWith<MissingScopeException> { TestOrgResolver.artifactCallerOf(key, ARTIFACTS_MANAGE) }
        // A person signed in with the scope does it, as an administrator.
        val person = AuthenticatedPrincipal(dave, PrincipalKind.USER, setOf(ARTIFACTS_WRITE))
        assertTrue(TestOrgResolver.artifactCallerOf(person, ARTIFACTS_MANAGE).admin)
        // Still needs the write scope, whoever asks.
        val reader = AuthenticatedPrincipal(dave, PrincipalKind.USER, setOf(ARTIFACTS_READ))
        assertFailsWith<MissingScopeException> { TestOrgResolver.artifactCallerOf(reader, ARTIFACTS_MANAGE) }
    }

    @Test
    fun scopesAndMembershipMakeTheCaller() = runTest {
        fun principal(
            kind: PrincipalKind,
            vararg scopes: String,
        ) = AuthenticatedPrincipal("alice", kind, scopes.toSet())
        val caller = TestOrgResolver.artifactCallerOf(principal(PrincipalKind.SERVICE, ARTIFACTS_WRITE), ARTIFACTS_READ)
        assertEquals(ORG_A, caller.orgId)
        assertEquals(ALICE.toString(), caller.memberId)
        assertFalse(caller.admin)
        TestOrgResolver.artifactCallerOf(principal(PrincipalKind.SERVICE, ARTIFACTS_READ), ARTIFACTS_READ)
        TestOrgResolver.artifactCallerOf(principal(PrincipalKind.USER, "user:*"), ARTIFACTS_WRITE)
        assertFailsWith<MissingScopeException> {
            TestOrgResolver.artifactCallerOf(principal(PrincipalKind.SERVICE, ARTIFACTS_READ), ARTIFACTS_WRITE)
        }
        assertFailsWith<MissingScopeException> {
            TestOrgResolver.artifactCallerOf(principal(PrincipalKind.SERVICE, "user:*"), ARTIFACTS_READ)
        }
        assertFailsWith<NotAMemberException> {
            TestOrgResolver.artifactCallerOf(
                AuthenticatedPrincipal("nobody", PrincipalKind.USER, setOf("user:*")),
                ARTIFACTS_READ,
            )
        }
        val admin = TestOrgResolver.artifactCallerOf(
            AuthenticatedPrincipal("dave", PrincipalKind.USER, setOf("user:*")),
            ARTIFACTS_READ,
        )
        assertTrue(admin.admin)
        val owner = TestOrgResolver.artifactCallerOf(
            AuthenticatedPrincipal("carol", PrincipalKind.USER, setOf("user:*")),
            ARTIFACTS_READ,
        )
        assertTrue(owner.admin)
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
