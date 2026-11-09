// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.domain.SecretsRepository
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.SecretsKey
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.presentation.SecretResponse
import run.ostrom.server.secrets.presentation.SecretWriteRequest
import run.ostrom.server.secrets.presentation.SecretsPageResponse
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: storage, encryption at rest, roles and org isolation. */
class SecretsPostgresTest {
    private val base = "/api/v1/secrets"

    @Test
    fun changingASecretFromTheConsoleNeedsASignInOfLessThanFiveMinutes() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            val write = SecretWriteRequest("GRAFANA_TOKEN", "glsa_value")
            val created = server.client.post(base) {
                bearerAuth("stale-admin-a")
                contentType(ContentType.Application.Json)
                setBody(write)
            }
            val fresh = server.client.post(base) {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(write)
            }
            assertEquals(HttpStatusCode.Created, fresh.status, fresh.bodyAsText())
            val rotated = server.client.put("$base/GRAFANA_TOKEN") {
                bearerAuth("stale-admin-a")
                contentType(ContentType.Application.Json)
                setBody(write)
            }
            val deleted = server.client.delete("$base/GRAFANA_TOKEN") { bearerAuth("stale-admin-a") }
            for (refused in listOf(created, rotated, deleted)) {
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertTrue("step_up_required" in refused.bodyAsText(), refused.bodyAsText())
            }
            val names = server.client.get(base) { bearerAuth("admin-a") }.body<SecretsPageResponse>().items
            assertEquals(listOf("GRAFANA_TOKEN"), names.map { it.name }, "Still there")
        }

    @Test
    fun anAdminStoresASecretThatIsEncryptedAtRestAndNeverReturned() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            val created = server.client.post(base) {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("GRAFANA_TOKEN", "glsa_very_secret_value"))
            }
            assertEquals(HttpStatusCode.Created, created.status)
            assertFalse("glsa_very_secret_value" in created.bodyAsText(), "The value never comes back")
            val secret = created.body<SecretResponse>()

            val stored = server.driver.fetchInOrg(
                TestCallers.orgA,
                Statement.create("SELECT envelope FROM secrets_entries"),
            ).getOrThrow().single().get("envelope").asString()
            assertFalse("glsa_very_secret_value" in stored, "Encrypted at rest")
            assertEquals("org", secret.scope)
            assertEquals(TestCallers.adminA.memberId.toString(), secret.updatedBy)
            val revealed = server.koin.get<RevealSecretsUseCase>().byName(TestCallers.orgA, listOf("GRAFANA_TOKEN"))
            assertEquals("glsa_very_secret_value", revealed.values.single().reveal())

            val listed = server.client.get(base) { bearerAuth("member-a") }.body<SecretsPageResponse>()
            assertEquals(listOf("GRAFANA_TOKEN"), listed.items.map { it.name })
            assertEquals(null, listed.next)
        }

    @Test
    fun anotherOrgNeitherSeesNorChangesTheSecret() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            server.client.post(base) {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("ORG_A_ONLY", "org-a-value-1"))
            }

            assertEquals(
                emptyList(),
                server.client.get(base) {
                    bearerAuth("admin-b")
                }.body<SecretsPageResponse>().items,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.delete("$base/ORG_A_ONLY") {
                    bearerAuth("admin-b")
                }.status,
            )
            val rotated = server.client.put("$base/ORG_A_ONLY") {
                bearerAuth("admin-b")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("ORG_A_ONLY", "stolen-value-1"))
            }
            assertEquals(HttpStatusCode.NotFound, rotated.status)
            assertEquals(
                emptyMap(),
                server.koin.get<RevealSecretsUseCase>().byName(TestCallers.orgB, listOf("ORG_A_ONLY")),
                "The server never reveals a secret to another org",
            )
            assertEquals(
                "org-a-value-1",
                server.koin.get<RevealSecretsUseCase>().byName(
                    TestCallers.orgA,
                    listOf("ORG_A_ONLY"),
                ).values.single().reveal(),
            )
        }

    @Test
    fun rolesAndAuthenticationAreEnforced() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(base).status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get(base) {
                bearerAuth("stranger")
            }.status,
            "A token without an org",
        )
        val refused = server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("SHARED", "shared-value-1"))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        val personal = server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("MINE", "my-value-123", scope = "personal"))
        }
        assertEquals(HttpStatusCode.Created, personal.status)
        val duplicate = server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("MINE", "my-value-456", scope = "personal"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        val invalid = server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("bad name", "x"))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        val badScope = server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("OK", "some-value-1", scope = "team"))
        }
        assertEquals(HttpStatusCode.BadRequest, badScope.status)
    }

    @Test
    fun rotateThenDelete() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("ROTATED", "first-value-1"))
        }
        server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("ROTATED", "personal-value-1", scope = "personal"))
        }

        val rotated = server.client.put("$base/ROTATED") {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("ROTATED", "second-value-2"))
        }.body<SecretResponse>()
        assertEquals(2, rotated.version)
        val value = server.koin.get<RevealSecretsUseCase>().byName(TestCallers.orgA, listOf("ROTATED"))
        assertEquals("second-value-2", value.values.single().reveal(), "The org secret, never the personal one")

        val mismatch = server.client.put("$base/ROTATED") {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("OTHER", "third-value-3"))
        }
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.put("$base/NOPE") {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("NOPE", "third-value-3"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            server.client.delete("$base/ROTATED?scope=personal") {
                bearerAuth("member-a")
            }.status,
        )
        assertEquals(HttpStatusCode.NoContent, server.client.delete("$base/ROTATED") { bearerAuth("admin-a") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.delete("$base/ROTATED") { bearerAuth("admin-a") }.status)
    }

    @Test
    fun listsArePagedByNameThenIdSoThatSharedNamesAreNotSkipped() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            for (name in listOf("A", "B", "C")) {
                server.client.post(base) {
                    bearerAuth("admin-a")
                    contentType(ContentType.Application.Json)
                    setBody(SecretWriteRequest(name, "value-of-$name"))
                }
            }
            server.client.post(base) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("B", "member-own-b", scope = "personal"))
            }

            val names = mutableListOf<String>()
            var after: String? = null
            do {
                val query = if (after == null) "" else "&after=${after.encodeURLParameter()}"
                val page = server.client.get("$base?limit=2$query") {
                    bearerAuth("admin-a")
                }.body<SecretsPageResponse>()
                names += page.items.map { it.name }
                after = page.next
            } while (after != null)
            assertEquals(listOf("A", "B", "B", "C"), names, "The org's B and member-a's B both appear")
            for (bad in listOf("B", "B/not-an-id")) {
                val refused = server.client.get("$base?after=$bad") { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.BadRequest, refused.status)
            }
        }

    @Test
    fun onlyCredentialsWithTheScopeReachTheSecrets() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            val write = SecretWriteRequest("MINE", "my-value-123", scope = "personal")
            for (token in listOf("agent-a", "device-a")) {
                assertEquals(HttpStatusCode.Forbidden, server.client.get(base) { bearerAuth(token) }.status, token)
                val written = server.client.post(base) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(write)
                }
                assertEquals(HttpStatusCode.Forbidden, written.status, token)
                assertEquals(HttpStatusCode.Forbidden, server.client.delete("$base/MINE") { bearerAuth(token) }.status)
            }
            assertEquals(
                HttpStatusCode.OK,
                server.client.get(base) {
                    bearerAuth("cli-a")
                }.status,
                "secrets:read lists",
            )
            val byCli = server.client.put("$base/MINE") {
                bearerAuth("cli-a")
                contentType(ContentType.Application.Json)
                setBody(write)
            }
            assertEquals(HttpStatusCode.Forbidden, byCli.status, "secrets:read does not write")
            // S2-52: a personal token holding secrets:write reads, but never makes a console change.
            assertEquals(HttpStatusCode.OK, server.client.get(base) { bearerAuth("cli-write-a") }.status)
            val writes = listOf(
                server.client.post(base) {
                    bearerAuth("cli-write-a")
                    contentType(ContentType.Application.Json)
                    setBody(write)
                },
                server.client.put("$base/MINE") {
                    bearerAuth("cli-write-a")
                    contentType(ContentType.Application.Json)
                    setBody(write)
                },
                server.client.delete("$base/MINE") { bearerAuth("cli-write-a") },
            )
            for (refused in writes) {
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertTrue("session in the console" in refused.bodyAsText())
            }
        }

    @Test
    fun rowLevelSecurityConfinesEveryQueryToItsOrg() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            server.client.post(base) {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("ORG_A_ONLY", "org-a-value-1"))
            }
            val unfiltered = Statement.create("SELECT name FROM secrets_entries")
            assertEquals(1, server.driver.fetchInOrg(TestCallers.orgA, unfiltered).getOrThrow().size)
            assertEquals(
                0,
                server.driver.fetchInOrg(TestCallers.orgB, unfiltered).getOrThrow().size,
                "No WHERE, no leak",
            )
            val stolen = Statement.create("UPDATE secrets_entries SET name = 'TAKEN'")
            assertEquals(0L, server.driver.executeInOrg(TestCallers.orgB, stolen).getOrThrow())
            val planted = Statement.create(
                "INSERT INTO secrets_entries " +
                    "(id, org_id, name, envelope, version, created_at, updated_at, updated_by) " +
                    "VALUES (:id, :org, 'PLANTED', 'x', 1, now(), now(), :org)",
            ).bind("id", Uuid.random()).bind("org", TestCallers.orgA)
            assertFails("Another org's row is refused") {
                server.driver.executeInOrg(TestCallers.orgB, planted).getOrThrow()
            }
            val names = server.driver.fetchInOrg(TestCallers.orgA, unfiltered).getOrThrow().map {
                it.get("name").asString()
            }
            assertEquals(listOf("ORG_A_ONLY"), names, "The failed write left nothing")
        }

    @Test
    fun withoutIdentityTheRealKeyLoadsAndEveryCallIsRefused() {
        val key = Base64.encode(ByteArray(AesGcmSecretCipher.KEY_BYTES) { 3 })
        val bare = module {
            single<ConfigSource> { MapConfigSource(mapOf(SecretsKey.KEY_VARIABLE to key)) }
            single<TokenAuthenticator> {
                object : TokenAuthenticator {
                    override suspend fun authenticate(token: String) =
                        AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(RelayScopes.ALL_USER))
                }
            }
        }
        serverTest(listOf(SecretsModule), bare) { server ->
            assertEquals(HttpStatusCode.NotFound, server.client.get(base) { bearerAuth("anyone") }.status)
            val cipher = server.koin.get<SecretCipher>()
            assertEquals("v", cipher.open(cipher.seal("v".encodeToByteArray(), "ctx"), "ctx")?.decodeToString())
        }
    }

    @Test
    fun theRepositoryReadsOnlyWhatExists() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(SecretWriteRequest("PRESENT", "present-value-1"))
        }
        val repository = server.koin.get<SecretsRepository>()
        val present = repository.findManyByName(TestCallers.orgA, null, listOf("PRESENT", "ABSENT")).single()
        assertEquals(emptyList(), repository.findManyByName(TestCallers.orgA, null, emptyList()))
        assertEquals(emptyList(), repository.findManyByName(TestCallers.orgA, Uuid.random(), listOf("PRESENT")))
        assertEquals(null, repository.get(TestCallers.orgA, Uuid.random()))
        assertEquals(listOf("PRESENT"), repository.getMany(TestCallers.orgA, listOf(present.id)).map { it.secret.name })
        assertEquals(emptyList(), repository.getMany(TestCallers.orgA, emptyList()))
        assertEquals(emptyList(), repository.getOrgSecretsByName(TestCallers.orgA, emptyList()))
        val epoch = Instant.parse("2026-10-30T00:00:00Z")
        assertEquals(
            null,
            repository.rotate(TestCallers.orgA, Uuid.random(), "v1:x", epoch, TestCallers.adminA.memberId),
        )
    }

    @Test
    fun aPersonalSecretIsRotatedByTheScopeOfThePath() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            server.client.post(base) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("MINE", "first-value-1", scope = "personal"))
            }
            val rotated = server.client.put("$base/MINE?scope=personal") {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("MINE", "second-value-2"))
            }
            assertEquals(2, rotated.body<SecretResponse>().version)
            val byBody = server.client.put("$base/MINE") {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(SecretWriteRequest("MINE", "third-value-3", scope = "personal"))
            }
            assertEquals(3, byBody.body<SecretResponse>().version, "The scope of the body wins over the path's")
        }
}
