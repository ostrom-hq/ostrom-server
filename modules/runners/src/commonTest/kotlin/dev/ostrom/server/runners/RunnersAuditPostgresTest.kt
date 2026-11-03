// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.runners.presentation.RegisteredRunnerDto
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * Against a real PostgreSQL: registering a runner mints a long-lived key that takes the organization's cloud sessions,
 * so registering and removing one are written to the organization's audit log, with who did it and never the key; a
 * refused attempt writes nothing.
 */
class RunnersAuditPostgresTest {
    private val audit = mutableListOf<OrgAuditRecord>()
    private val overrides = module {
        includes(testIdentities)
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
    }

    @Test
    fun registeringAndRemovingARunnerAreInTheOrganizationsAuditLog() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val body = """{"name":"build-1","labels":["linux","gpu"],"version":"1.2.0"}"""
            val registered = server.register("dana", body)
            assertEquals(HttpStatusCode.Created, registered.status)
            val created = registered.body<RegisteredRunnerDto>()

            val line = audit.single()
            assertEquals(ORG_A, line.orgId)
            assertEquals("dana", line.actor.subjectId)
            assertEquals("runner.registered", line.event)
            assertEquals("runner", line.subjectType)
            assertEquals(created.id, line.subjectId)
            assertEquals(mapOf("name" to "build-1", "labels" to "linux,gpu", "version" to "1.2.0"), line.details)
            assertFalse(created.token in audit.toString(), "the key is never in the audit log")

            // A member's refused removal, and an unknown runner, write nothing.
            assertEquals(HttpStatusCode.Forbidden, server.remove("alice", created.id).status)
            assertEquals(HttpStatusCode.NotFound, server.remove("dana", Uuid.random().toString()).status)
            assertEquals(1, audit.size)

            assertEquals(HttpStatusCode.NoContent, server.remove("dana", created.id).status)
            assertEquals(2, audit.size)
            val removed = audit.last()
            assertEquals(ORG_A, removed.orgId)
            assertEquals("dana", removed.actor.subjectId)
            assertEquals("runner.deleted", removed.event)
            assertEquals("runner", removed.subjectType)
            assertEquals(created.id, removed.subjectId)
        }

    @Test
    fun aRefusedRegistrationWritesNothing() = serverTest(listOf(RunnersModule), overrides) { server ->
        val refused = listOf(
            "alice" to """{"name":"b","labels":[]}""",
            "dana-cli" to """{"name":"b","labels":[]}""",
            "dana" to """{"name":"b","labels":["a b"]}""",
        )
        for ((token, body) in refused) {
            assertFalse(server.register(token, body).status.isSuccess(), token)
        }
        assertEquals(emptyList(), audit)
    }

    private suspend fun TestServer.register(token: String, body: String) = client.post("/api/v1/runners") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.remove(token: String, id: String) =
        client.delete("/api/v1/runners/$id") { bearerAuth(token) }
}
