package dev.ostrom.server.secrets

import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.presentation.CreateSecretRequest
import dev.ostrom.server.secrets.presentation.RotateSecretRequest
import dev.ostrom.server.secrets.presentation.SecretResponse
import dev.ostrom.server.secrets.presentation.SecretsPageResponse
import dev.ostrom.server.testing.serverTest
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: storage, encryption at rest, roles and org isolation. */
class SecretsPostgresTest {
    private val base = "/v1/orgs/${TestCallers.orgA}/secrets"

    @Test
    fun anAdminStoresASecretThatIsEncryptedAtRestAndNeverReturned() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        val created = server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("GRAFANA_TOKEN", "glsa_very_secret_value"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertFalse("glsa_very_secret_value" in created.bodyAsText(), "The value never comes back")
        val secret = created.body<SecretResponse>()

        val stored = server.driver.fetchAll("SELECT envelope FROM secrets_entries").getOrThrow().single().get("envelope").asString()
        assertFalse("glsa_very_secret_value" in stored, "Encrypted at rest")
        val revealed = server.koin.get<RevealSecretsUseCase>()(TestCallers.orgA, listOf(Uuid.parse(secret.id)))
        assertEquals("glsa_very_secret_value", revealed.values.single().reveal())

        val listed = server.client.get(base) { bearerAuth("member-a") }.body<SecretsPageResponse>()
        assertEquals(listOf("GRAFANA_TOKEN"), listed.secrets.map { it.name })
        assertEquals(null, listed.next)
    }

    @Test
    fun anotherOrgNeitherSeesNorChangesTheSecret() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        val secret = server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("ORG_A_ONLY", "org-a-value-1"))
        }.body<SecretResponse>()

        assertEquals(HttpStatusCode.NotFound, server.client.get(base) { bearerAuth("admin-b") }.status, "Not a member of org A")
        val orgB = "/v1/orgs/${TestCallers.orgB}/secrets"
        assertEquals(emptyList(), server.client.get(orgB) { bearerAuth("admin-b") }.body<SecretsPageResponse>().secrets)
        assertEquals(HttpStatusCode.NotFound, server.client.delete("$orgB/${secret.id}") { bearerAuth("admin-b") }.status)
        assertEquals(
            emptyMap(),
            server.koin.get<RevealSecretsUseCase>()(TestCallers.orgB, listOf(Uuid.parse(secret.id))),
            "The server never reveals a secret to another org",
        )
    }

    @Test
    fun rolesAndAuthenticationAreEnforced() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(base).status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("/v1/orgs/not-a-uuid/secrets") { bearerAuth("admin-a") }.status)
        val refused = server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("SHARED", "shared-value-1"))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        val personal = server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("MINE", "my-value-123", personal = true))
        }
        assertEquals(HttpStatusCode.Created, personal.status)
        val duplicate = server.client.post(base) {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("MINE", "my-value-456", personal = true))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        val invalid = server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("bad name", "x"))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }

    @Test
    fun rotateThenDelete() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        val secret = server.client.post(base) {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(CreateSecretRequest("ROTATED", "first-value-1"))
        }.body<SecretResponse>()

        val rotated = server.client.put("$base/${secret.id}") {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(RotateSecretRequest("second-value-2"))
        }.body<SecretResponse>()
        assertEquals(2, rotated.version)
        val value = server.koin.get<RevealSecretsUseCase>()(TestCallers.orgA, listOf(Uuid.parse(secret.id)))
        assertEquals("second-value-2", value.values.single().reveal())

        assertEquals(HttpStatusCode.NotFound, server.client.put("$base/nope") {
            bearerAuth("admin-a")
            contentType(ContentType.Application.Json)
            setBody(RotateSecretRequest("third-value-3"))
        }.status)
        assertEquals(HttpStatusCode.NoContent, server.client.delete("$base/${secret.id}") { bearerAuth("admin-a") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.delete("$base/${secret.id}") { bearerAuth("admin-a") }.status)
    }

    @Test
    fun listsArePagedByName() = serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
        for (name in listOf("A", "B", "C")) {
            server.client.post(base) {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(CreateSecretRequest(name, "value-of-$name"))
            }
        }

        val first = server.client.get("$base?limit=2") { bearerAuth("admin-a") }.body<SecretsPageResponse>()
        assertEquals(listOf("A", "B"), first.secrets.map { it.name })
        assertEquals("B", first.next)
        val second = server.client.get("$base?limit=2&after=B") { bearerAuth("admin-a") }.body<SecretsPageResponse>()
        assertEquals(listOf("C"), second.secrets.map { it.name })
        assertEquals(null, second.next)
    }
}
