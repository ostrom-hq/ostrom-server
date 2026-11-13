// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.server.cloud.domain.CloudClusterAccess
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.cloud.presentation.CloudClusterListResponse
import run.ostrom.server.cloud.presentation.CloudClusterResponse
import run.ostrom.server.cloud.presentation.CloudProfileListResponse
import run.ostrom.server.cloud.presentation.CloudProfileResponse
import run.ostrom.server.cloud.presentation.CloudRuntimeResponse
import run.ostrom.server.cloud.presentation.cloudAdmin
import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The cloud administration on a real server: who may, what is stored, what never comes back. */
class CloudAdminRoutesPostgresTest {
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                    "alice", "bob", "carol" -> person(token)
                    "alicekey" -> AuthenticatedPrincipal(ALICE.toString(), PrincipalKind.USER, CLOUD_SCOPES)
                    else -> null
                }
            }
        }
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<InstanceAdministrators> { InstanceAdministrators { it == ALICE } }
        // Alice administers organization A, Bob is a member of it, Carol administers organization B.
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    when (principal.subjectId) {
                        ALICE.toString() -> Caller(ORG_A, ALICE, MemberRole.ADMIN)
                        BOB.toString() -> Caller(ORG_A, BOB, MemberRole.MEMBER)
                        CAROL.toString() -> Caller(ORG_B, CAROL, MemberRole.OWNER)
                        else -> null
                    }
            }
        }
        single<ConfigSource> {
            MapConfigSource(
                mapOf("OSTROM_PUBLIC_URL" to "https://api.acme.dev/", "OSTROM_RUNNERS_DRIVER" to "kubernetes"),
            )
        }
    }

    private fun person(name: String) = AuthenticatedPrincipal(
        mapOf("alice" to ALICE, "bob" to BOB, "carol" to CAROL).getValue(name).toString(),
        PrincipalKind.USER,
        setOf(ALL_USER_SCOPES),
        channel = PrincipalChannel.BROWSER,
    )

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: String): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (method == "POST") post(path, build) else put(path, build)
    }

    private fun cluster(name: String, version: Int, credentials: Boolean = true): String {
        val secrets = if (credentials) ""","caCertificate":"$PEM_JSON","serviceAccountToken":"tok.secret"""" else ""
        return """{"name":"$name","apiUrl":"https://k8s.acme.dev","allowedNamespaces":["sessions"],""" +
            """"maxRunningPods":5,"version":$version$secrets}"""
    }

    private fun profile(clusterId: String, version: Int) = """
        {"name":"Default","version":$version,"assignedMemberIds":["$BOB"],
        "spec":{"clusterId":"$clusterId","namespace":"sessions","image":"ghcr.io/acme/session:1"}}
    """.trimIndent()

    @Test
    fun theRuntimeIsSeededOnceThenSavedByAnAdministratorOverTheOneRead() = serverTest(
        listOf(CloudModule),
        overrides,
    ) { server ->
        val http = server.client
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/cloud/runtime").status)
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/v1/cloud/runtime") { bearerAuth("bob") }.status)
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/v1/cloud/runtime") { bearerAuth("alicekey") }.status)

        val seeded = http.get("/api/v1/cloud/runtime") { bearerAuth("alice") }.body<CloudRuntimeResponse>()
        assertTrue(seeded.enabled)
        assertEquals("https://api.acme.dev", seeded.effectiveServerUrl)
        val body = """
            {"enabled":false,"runnerBinary":"/usr/local/bin/ostrom","workspaceAccessMode":"ReadWriteOnce",
            "failedWorkspaceRetentionDays":7,"maxRunningSessionsPerMember":2,"maxRetainedSessionsPerMember":20,
            "memberTransfers":"disabled","serverUrl":"https://pods.acme.dev","updatedAt":"${seeded.updatedAt}"}
        """.trimIndent()
        assertEquals(HttpStatusCode.Forbidden, http.send("PUT", "/api/v1/cloud/runtime", "bob", body).status)
        val saved = http.send("PUT", "/api/v1/cloud/runtime", "alice", body).body<CloudRuntimeResponse>()
        assertFalse(saved.enabled)
        assertEquals("https://pods.acme.dev", saved.effectiveServerUrl)
        assertEquals("disabled", server.koin.get<CloudRuntimeSource>().current().memberTransfers)
        val stale = http.send("PUT", "/api/v1/cloud/runtime", "alice", body)
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue("cloud_runtime_conflict" in stale.bodyAsText())
        val invalid = http.send("PUT", "/api/v1/cloud/runtime", "alice", body.replace(seeded.updatedAt!!, "soon"))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val again = http.get("/api/v1/cloud/runtime") { bearerAuth("alice") }.body<CloudRuntimeResponse>()
        assertEquals(saved, again)
    }

    @Test
    fun clustersAreTheInstanceAdministratorsAndTheirCredentialsNeverComeBack() = serverTest(
        listOf(CloudModule),
        overrides,
    ) { server ->
        val http = server.client
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/v1/cloud/clusters") { bearerAuth("carol") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            http.send("POST", "/api/v1/cloud/clusters", "carol", cluster("prod", 0)).status,
        )
        val created = http.send("POST", "/api/v1/cloud/clusters", "alice", cluster("prod", 0))
        assertEquals(HttpStatusCode.Created, created.status)
        assertFalse("secret" in created.bodyAsText())
        val prod = created.body<CloudClusterResponse>()
        assertTrue(prod.serviceAccountToken.configured)
        assertEquals(
            HttpStatusCode.Conflict,
            http.send("POST", "/api/v1/cloud/clusters", "alice", cluster("prod", 0)).status,
        )
        val metadata = cluster("meta", 0).replace("k8s.acme.dev", "169.254.169.254")
        assertEquals(
            HttpStatusCode.BadRequest,
            http.send("POST", "/api/v1/cloud/clusters", "alice", metadata).status,
        )

        val path = "/api/v1/cloud/clusters/${prod.id}"
        assertEquals(HttpStatusCode.Forbidden, http.send("PUT", path, "bob", cluster("prod", 1, false)).status)
        val changed = http.send("PUT", path, "alice", cluster("prod", 1, false)).body<CloudClusterResponse>()
        assertEquals(2, changed.version)
        val kept = server.koin.get<CloudClusterAccess>().credentials(Uuid.parse(prod.id))
        assertEquals("tok.secret", kept?.serviceAccountToken)
        assertEquals(HttpStatusCode.Conflict, http.send("PUT", path, "alice", cluster("prod", 1, false)).status)
        for (unknown in listOf("not-an-id", Uuid.random().toString())) {
            val missing = http.send("PUT", "/api/v1/cloud/clusters/$unknown", "alice", cluster("prod", 1, false))
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }
        http.send("POST", "/api/v1/cloud/clusters", "alice", cluster("staging", 0))
        val list = http.get("/api/v1/cloud/clusters") { bearerAuth("alice") }.body<CloudClusterListResponse>()
        assertEquals(listOf("prod", "staging"), list.clusters.map { it.name })
        assertEquals(changed, list.clusters.first())
    }

    @Test
    fun aProfileIsItsOrganizationsAndEachSavePublishesAVersion() = serverTest(
        listOf(CloudModule),
        overrides,
    ) { server ->
        val http = server.client
        val clusterId = http.send("POST", "/api/v1/cloud/clusters", "alice", cluster("prod", 0))
            .body<CloudClusterResponse>().id
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/cloud/profiles").status)
        // A key never reads nor writes a team's profiles, whatever its scopes (TeamCode: they are its people's).
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/v1/cloud/profiles") { bearerAuth("alicekey") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            http.send("POST", "/api/v1/cloud/profiles", "bob", profile(clusterId, 0)).status,
        )
        val created = http.send("POST", "/api/v1/cloud/profiles", "alice", profile(clusterId, 0))
        assertEquals(HttpStatusCode.Created, created.status)
        val first = created.body<CloudProfileResponse>()
        val path = "/api/v1/cloud/profiles/${first.id}"
        val second = http.send("PUT", path, "alice", profile(clusterId, 1)).body<CloudProfileResponse>()
        assertEquals(2, second.version)
        assertEquals(HttpStatusCode.Conflict, http.send("PUT", path, "alice", profile(clusterId, 1)).status)
        val invalid = http.send("PUT", path, "alice", profile(Uuid.random().toString(), 2))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertTrue("invalid_cloud_profile" in invalid.bodyAsText())

        // Bob sees the profile assigned to him; organization B neither sees nor changes it.
        val bobs = http.get("/api/v1/cloud/profiles") { bearerAuth("bob") }.body<CloudProfileListResponse>()
        assertEquals(listOf(second), bobs.profiles)
        val theirs = http.get("/api/v1/cloud/profiles") { bearerAuth("carol") }.body<CloudProfileListResponse>()
        assertTrue(theirs.profiles.isEmpty())
        assertEquals(HttpStatusCode.NotFound, http.send("PUT", path, "carol", profile(clusterId, 2)).status)
        assertEquals(
            HttpStatusCode.NotFound,
            http.send("PUT", "/api/v1/cloud/profiles/not-an-id", "alice", profile(clusterId, 2)).status,
        )
        val repository = server.koin.get<CloudProfilesRepository>()
        assertNull(repository.get(ORG_B, Uuid.parse(first.id)))
        assertEquals(2, repository.get(ORG_A, Uuid.parse(first.id))?.version)
    }

    @Test
    fun aCredentialThatIsNoAccountIsNobody() = runTest {
        val service = AuthenticatedPrincipal("svc_1", PrincipalKind.SERVICE, setOf("cloud:write"))

        assertNull(service.cloudAdmin().userId)
        assertFalse(service.cloudAdmin().person)
        assertTrue(person("alice").cloudAdmin().person)
    }

    private companion object {
        val CLOUD_SCOPES = setOf("cloud:read", "cloud:write")
        val CAROL: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ca20")
        val PEM_JSON = PEM.replace("\n", "\\n")
    }
}
