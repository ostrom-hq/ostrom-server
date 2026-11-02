// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.runners.presentation.CloudSessionResponse
import dev.ostrom.server.runners.presentation.CreateCloudSessionRequest
import dev.ostrom.server.runners.presentation.TerminalTicketResponse
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A terminal ticket of a cloud session is issued to its owner only: a member of another organization (carol) or
 * another member of the same one (bob) gets 404, and the session is unchanged when its owner reads it back.
 */
class RunnersIsolationPostgresTest {
    @Test
    fun anotherOrganizationGetsNoTerminalTicket() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val session = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "claude", name = "Mine"))
        }.body<CloudSessionResponse>()
        val path = "/api/v1/cloud/sessions/${session.id}"
        val before = server.client.get(path) { bearerAuth("alice") }.body<CloudSessionResponse>()

        for (stranger in listOf("carol", "bob")) {
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.post("$path/terminal/ticket") { bearerAuth(stranger) }.status,
                stranger,
            )
        }

        assertEquals(before, server.client.get(path) { bearerAuth("alice") }.body<CloudSessionResponse>())
        val own = server.client.post("$path/terminal/ticket") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.OK, own.status, "The owner still gets a ticket")
        assertTrue(own.body<TerminalTicketResponse>().ticket.startsWith("ostrom_term_"))
    }
}
