// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactQuotas
import run.ostrom.server.artifacts.domain.storage.ArtifactRetention
import run.ostrom.server.artifacts.domain.storage.ArtifactSettings
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.PublicSharing
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import run.ostrom.server.artifacts.fakes.ALICE
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.ORG_B
import run.ostrom.server.artifacts.fakes.TestCipher
import run.ostrom.server.artifacts.fakes.testIdentities
import run.ostrom.server.artifacts.infrastructure.inTenant
import run.ostrom.server.artifacts.infrastructure.storage.S3ObjectStore
import run.ostrom.server.artifacts.presentation.ArtifactPolicyResponse
import run.ostrom.server.artifacts.presentation.ArtifactSettingsResponse
import run.ostrom.server.artifacts.presentation.ArtifactStorageResponse
import run.ostrom.server.artifacts.presentation.ArtifactStorageTestResponse
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the bucket and settings tables, their routes and the publication policy. */
class ArtifactStoragePostgresTest {
    private val t0 = Instant.parse("2026-11-13T10:00:00Z")
    private val bucket = InMemoryBucket()
    private val overrides = module {
        includes(testIdentities)
        single<SecretCipher> { TestCipher }
        single<ArtifactObjectStore> { bucket }
    }

    private fun input(org: String = "a", secret: String? = "secret-$org") = ArtifactStorageInput(
        "https://s3.example.com", "auto", "bucket-$org", "", false, "AKIA$org", secret, ServerSideEncryption.NONE,
    )

    @Test
    fun theRepositorySealsTheSecretPerOrganizationAndComparesVersions() = serverTest(
        listOf(ArtifactsModule),
        module { single<SecretCipher> { TestCipher } },
    ) { server ->
        assertTrue(server.koin.get<ArtifactObjectStore>() is S3ObjectStore)
        val repository = server.koin.get<ArtifactStorageRepository>()
        assertNull(repository.overview(ORG_A).storage)
        assertNull(repository.connection(ORG_A))
        assertFalse(repository.deleteStorage(ORG_A, t0))
        val saved = assertNotNull(repository.saveStorage(ORG_A, 0, input(), t0))
        assertEquals(1, saved.version)
        assertNull(repository.saveStorage(ORG_A, 0, input(), t0))
        assertNull(repository.saveStorage(ORG_A, 5, input(), t0))
        val updated = assertNotNull(repository.saveStorage(ORG_A, 1, input(secret = null), t0))
        assertEquals(2, updated.version)
        assertEquals("secret-a", repository.connection(ORG_A)?.secretAccessKey)
        assertEquals(2, repository.connection(ORG_A)?.version)
        assertFalse(repository.recordTest(ORG_A, 1, true, t0))
        assertTrue(repository.recordTest(ORG_A, 2, true, t0))
        assertEquals(t0, repository.overview(ORG_A).storage?.verifiedAt)
        assertTrue(repository.recordTest(ORG_A, 2, false, t0))
        assertNull(repository.overview(ORG_A).storage?.verifiedAt)
        // Row-level security: another organization sees none of it.
        assertNull(repository.overview(ORG_B).storage)
        // An envelope copied to another organization's row does not open there.
        repository.saveStorage(ORG_B, 0, input("b"), t0)
        val envelope = server.driver.inTenant(ORG_A) {
            fetchAll(Statement.create("SELECT secret_sealed FROM artifacts_storage")).getOrThrow().single()
                .get("secret_sealed").asString()
        }
        server.driver.inTenant(ORG_B) {
            execute(Statement.create("UPDATE artifacts_storage SET secret_sealed = :s").bind("s", envelope))
                .getOrThrow()
        }
        assertNull(repository.connection(ORG_B))
        assertEquals("secret-a", repository.connection(ORG_A)?.secretAccessKey)
    }

    @Test
    fun theRepositoryKeepsTheSettingsAndTurnsThemOffWithTheBucket() = serverTest(
        listOf(ArtifactsModule),
        module { single<SecretCipher> { TestCipher } },
    ) { server ->
        val repository = server.koin.get<ArtifactStorageRepository>()
        assertEquals(ArtifactSettings(), repository.overview(ORG_A).settings)
        val wanted = ArtifactSettings(
            enabled = true,
            whoCanPublish = WhoCanPublish.ADMINS,
            publicSharing = PublicSharing.OFF,
            quotas = ArtifactQuotas(ArtifactQuotas.GIB, 10 * ArtifactQuotas.GIB, 20, 5),
            retention = ArtifactRetention(30, 365, 10),
        )
        val saved = assertNotNull(repository.saveSettings(ORG_A, wanted, t0))
        assertEquals(wanted.copy(version = 1, updatedAt = t0), saved)
        assertEquals(saved, repository.overview(ORG_A).settings)
        assertNull(repository.saveSettings(ORG_A, wanted, t0))
        assertNull(repository.saveSettings(ORG_A, wanted.copy(version = 4), t0))
        val again = assertNotNull(repository.saveSettings(ORG_A, saved.copy(retention = ArtifactRetention()), t0))
        assertEquals(2, again.version)
        assertNull(again.retention.personalDays)
        repository.saveStorage(ORG_A, 0, input(), t0)
        assertFalse(repository.overview(ORG_A).inUse)
        val artifact = Artifact(Uuid.random(), ORG_A, ALICE.toString(), "T", ArtifactAccess.PERSONAL, 1, null, t0, t0)
        val file = ArtifactFile("a.txt", "sha-a", 3, "text/plain; charset=utf-8")
        server.koin.get<ArtifactsRepository>().create(
            artifact,
            ArtifactVersion(artifact.id, 1, "a.txt", listOf(file), t0, artifact.ownerId),
        )
        assertTrue(repository.overview(ORG_A).inUse)
        assertTrue(repository.deleteStorage(ORG_A, t0))
        val off = repository.overview(ORG_A).settings
        assertFalse(off.enabled)
        assertEquals(3, off.version)
        // Settings already off are left as they are.
        repository.saveStorage(ORG_A, 0, input(), t0)
        assertTrue(repository.deleteStorage(ORG_A, t0))
        assertEquals(3, repository.overview(ORG_A).settings.version)
    }

    private suspend fun HttpClient.saveStorage(token: String, body: String): HttpResponse =
        put("/api/v1/artifacts/storage") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.saveSettings(token: String, body: String): HttpResponse =
        put("/api/v1/artifacts/settings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun settingsBody(version: Int, who: String = "everyone", sharing: String = "owners_of_artifacts") =
        """{"version":$version,"enabled":true,"whoCanPublish":"$who","publicSharing":"$sharing",""" +
            """"quotaMemberBytes":2147483648,"quotaOrgBytes":53687091200,"quotaArtifactsPerMember":500,""" +
            """"quotaPublishesPerHour":60,"personalRetentionDays":30,"maxVersionsPerArtifact":50}"""

    private val storageBody = """{"version":0,"endpoint":" https://s3.example.com/ ","region":"auto",""" +
        """"bucket":"artifacts","accessKeyId":"AKIAEXAMPLE","secretAccessKey":"very-secret-key",""" +
        """"serverSideEncryption":"AES256"}"""

    private suspend fun HttpClient.policy(token: String): ArtifactPolicyResponse =
        get("/api/v1/artifacts/policy") { bearerAuth(token) }.body()

    @Test
    fun onlyASignedInOwnerOrAdministratorConfiguresTheBucket() = serverTest(listOf(ArtifactsModule), overrides) {
        val client = it.client
        // Not a member, not an administrator's key, not a service key.
        for (token in listOf("alice", "dave-agent", "writer")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/artifacts/storage") { bearerAuth(token) }.status)
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/artifacts/storage") { bearerAuth("nobody") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/artifacts/storage").status)
        val empty = client.get("/api/v1/artifacts/storage") { bearerAuth("dave") }.body<ArtifactStorageResponse>()
        assertFalse(empty.configured)
        assertEquals(ArtifactPolicyResponse(false, "artifact_storage_missing"), client.policy("alice"))

        assertEquals(
            HttpStatusCode.BadRequest,
            client.saveStorage("dave", storageBody.replace("AES256", "aws:kms")).status,
        )
        val saved = client.saveStorage("dave", storageBody)
        assertEquals(HttpStatusCode.OK, saved.status)
        assertFalse("very-secret-key" in saved.bodyAsText())
        val stored = saved.body<ArtifactStorageResponse>()
        assertEquals("https://s3.example.com", stored.endpoint)
        assertTrue(stored.configured && stored.secretConfigured && !stored.inUse)
        assertEquals("AES256", stored.serverSideEncryption)
        assertNull(stored.verifiedAt)
        assertEquals(HttpStatusCode.Conflict, client.saveStorage("dave", storageBody).status)
        // Another organization's owner sees none of it.
        val other = client.get("/api/v1/artifacts/storage") { bearerAuth("carol") }.body<ArtifactStorageResponse>()
        assertFalse(other.configured)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/artifacts/storage") { bearerAuth("dave") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/artifacts/storage") { bearerAuth("dave") }.status)
    }

    @Test
    fun aTestedBucketTurnsArtifactsOnAndThePolicyFollows() = serverTest(listOf(ArtifactsModule), overrides) {
        val client = it.client
        client.saveStorage("dave", storageBody)
        val unverified = client.saveSettings("dave", settingsBody(0))
        assertEquals(HttpStatusCode.Conflict, unverified.status)
        assertTrue("artifact_storage_unverified" in unverified.bodyAsText())
        val test = client.post("/api/v1/artifacts/storage/test") { bearerAuth("dave") }
            .body<ArtifactStorageTestResponse>()
        assertTrue(test.ok)
        assertEquals(listOf("put", "get", "list", "delete"), test.steps.map { step -> step.step })
        val verified = client.get("/api/v1/artifacts/storage") { bearerAuth("dave") }.body<ArtifactStorageResponse>()
        assertNotNull(verified.verifiedAt)
        val disabled = client.get("/api/v1/artifacts/settings") { bearerAuth("dave") }.body<ArtifactSettingsResponse>()
        assertEquals("artifacts_disabled", disabled.inactiveReason)
        assertFalse(disabled.active)

        for (body in listOf(settingsBody(0, who = "nobody"), settingsBody(0, sharing = "world"))) {
            assertEquals(HttpStatusCode.BadRequest, client.saveSettings("dave", body).status)
        }
        val on = client.saveSettings("dave", settingsBody(0)).body<ArtifactSettingsResponse>()
        assertTrue(on.active)
        assertNull(on.inactiveReason)
        assertEquals(30, on.personalRetentionDays)
        assertEquals(1, on.version)
        assertNotNull(on.updatedAt)

        assertEquals(ArtifactPolicyResponse(true), client.policy("alice"))
        assertEquals(ArtifactPolicyResponse(false, "credential_scope"), client.policy("reader"))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/artifacts/policy") { bearerAuth("cloud") }.status)
        client.saveSettings("dave", settingsBody(1, who = "admins"))
        assertEquals(ArtifactPolicyResponse(false, "artifact_publish_forbidden"), client.policy("alice"))

        client.delete("/api/v1/artifacts/storage") { bearerAuth("dave") }
        val after = client.get("/api/v1/artifacts/settings") { bearerAuth("dave") }.body<ArtifactSettingsResponse>()
        assertFalse(after.enabled)
        assertEquals("artifact_storage_missing", after.inactiveReason)
    }
}
