// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.AgentDirective
import run.ostrom.server.gateway.domain.DirectiveRepository
import run.ostrom.server.gateway.domain.DirectiveWrite
import run.ostrom.server.gateway.domain.Pace
import run.ostrom.server.gateway.domain.PacingAdvice
import run.ostrom.server.gateway.infrastructure.sessionDigest
import run.ostrom.server.gateway.presentation.DirectiveClaimDto
import run.ostrom.server.gateway.presentation.DirectiveResponseDto
import run.ostrom.server.gateway.presentation.PacingPreviewDto
import run.ostrom.server.gateway.presentation.PacingSettingsDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The capacity controller's routes on real PostgreSQL and HTTP (TeamCode's `DirectiveRoutes` and `PacingSettingsRoutes`
 * cases): who reads, who changes, an agent token only for its own session, nothing cached.
 */
class PacingRoutesPostgresTest : GatewayHttpFixture() {
    private suspend fun TestServer.postJson(path: String, token: String?, body: String): HttpResponse =
        client.post(path) {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.advise(scope: String) {
        val now = Clock.System.now()
        val advice = PacingAdvice(Pace.SLOW_DOWN, 0, 5.seconds, "Reduce optional work.")
        val directive = AgentDirective(Uuid.random().toString(), Gw.memberA.memberId.toString(), advice, now, now + 3.minutes)
        koin.get<DirectiveRepository>().save(Gw.orgA, listOf(DirectiveWrite(Gw.memberA.memberId, scope, directive, "k", null)))
    }

    @Test
    fun membersReadTheSettingsAndOnlyAnAdminSignedInChangesThem() = serverTest(modules, overrides) { server ->
        with(server) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/policy/pacing").status)
            val read = client.get("/api/v1/policy/pacing") { bearerAuth("use-a") }
            assertEquals(PacingSettingsDto(observationOnly = false, autoPoolSize = false), read.body<PacingSettingsDto>())
            assertEquals(HttpStatusCode.Forbidden, putJson("/api/v1/policy/pacing", "member-a", PacingSettingsDto(true)).status)
            assertEquals(HttpStatusCode.Forbidden, putJson("/api/v1/policy/pacing", "use-a", PacingSettingsDto(true)).status)
            assertEquals(HttpStatusCode.Forbidden, putJson("/api/v1/policy/pacing", "stale-admin-a", PacingSettingsDto(true)).status)
            val changed = putJson("/api/v1/policy/pacing", "admin-a", PacingSettingsDto(observationOnly = true))
            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            assertEquals(PacingSettingsDto(true, false), changed.body<PacingSettingsDto>())
            assertEquals(PacingSettingsDto(true, true), putJson("/api/v1/policy/pacing", "admin-a", PacingSettingsDto(autoPoolSize = true)).body())
            val other = client.get("/api/v1/policy/pacing") { bearerAuth("admin-b") }
            assertEquals(PacingSettingsDto(false, false), other.body<PacingSettingsDto>(), "Another org's settings")
        }
    }

    @Test
    fun theAdviceIsReadClaimedAndAcknowledgedWithinItsSession() = serverTest(modules, overrides) { server ->
        with(server) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/directives/current").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/directives/current") { bearerAuth("use-a") }.status)
            val none = client.get("/api/v1/directives/current") { bearerAuth("guidance-a") }
            assertEquals("no-store", none.headers[HttpHeaders.CacheControl])
            assertNull(none.body<DirectiveResponseDto>().directive)
            advise("")
            advise(sessionDigest("session-1"))
            val wide = client.get("/api/v1/directives/current") { bearerAuth("member-a") }.body<DirectiveResponseDto>()
            assertEquals("slow_down", wide.directive!!.pace)
            assertEquals(5_000, wide.directive!!.minIntervalMillis)
            val preview = client.get("/api/v1/directives/preview") { bearerAuth("use-a") }.body<PacingPreviewDto>()
            assertEquals(wide.directive, preview.directive)

            val scoped = postJson("/api/v1/directives/current", "session-1", """{"sessionId":"session-1"}""")
            assertNotNull(scoped.body<DirectiveResponseDto>().directive)
            assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/directives/current", "session-1", """{"sessionId":"session-2"}""").status)
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/directives/current", "member-a", """{"sessionId":"a b"}""").status)

            val claim = postJson("/api/v1/directives/claim", "session-1", """{"sessionId":"session-1","stage":"emitted_to_hook"}""")
            val claimed = claim.body<DirectiveClaimDto>()
            assertNotNull(claimed.directive)
            assertNull(
                postJson("/api/v1/directives/claim", "session-1", """{"sessionId":"session-1","stage":"emitted_to_hook"}""")
                    .body<DirectiveClaimDto>().claimId,
            )
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/directives/claim", "session-1", """{"sessionId":"session-1","stage":"x"}""").status)
            val receipt = """{"id":"${claimed.directive!!.id}","stage":"emitted_to_hook","sessionId":"session-1","claimId":"${claimed.claimId}"}"""
            assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/directives/receipt", "session-1", receipt).status)
            assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/directives/receipt", "member-a", receipt).status, "Another token's claim")
            assertEquals(
                HttpStatusCode.Conflict,
                postJson("/api/v1/directives/receipt", "member-a", """{"id":"gone","stage":"received_by_cli"}""").status,
            )
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/directives/receipt", null, receipt).status)
            // Another org's member reads nothing of Ada's.
            assertNull(client.get("/api/v1/directives/current") { bearerAuth("admin-b") }.body<DirectiveResponseDto>().directive)
            // In observation mode the agent gets nothing, the preview still shows it.
            putJson("/api/v1/policy/pacing", "admin-a", PacingSettingsDto(observationOnly = true))
            assertNull(client.get("/api/v1/directives/current") { bearerAuth("member-a") }.body<DirectiveResponseDto>().directive)
            assertNotNull(client.get("/api/v1/directives/preview") { bearerAuth("member-a") }.body<PacingPreviewDto>().directive)
        }
    }
}
