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
        val member = Gw.memberA.memberId
        val directive = AgentDirective(Uuid.random().toString(), member.toString(), advice, now, now + 3.minutes)
        koin.get<DirectiveRepository>().save(Gw.orgA, listOf(DirectiveWrite(member, scope, directive, "k", null)))
    }

    @Test
    fun membersReadTheSettingsAndOnlyAnAdminSignedInChangesThem() = serverTest(modules, overrides) { server ->
        with(server) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/policy/pacing").status)
            val read = client.get("/api/v1/policy/pacing") { bearerAuth("use-a") }
            assertEquals(PacingSettingsDto(observationOnly = false, autoPoolSize = false), read.body())
            // the CLI token reads the advice (`guidance`) but never changes the settings, even an administrator's
            for (token in listOf("member-a", "use-a", "stale-admin-a", "cli-admin-a")) {
                val refused = putJson("/api/v1/policy/pacing", token, PacingSettingsDto(true))
                assertEquals(HttpStatusCode.Forbidden, refused.status, token)
            }
            val changed = putJson("/api/v1/policy/pacing", "admin-a", PacingSettingsDto(observationOnly = true))
            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            assertEquals(PacingSettingsDto(true, false), changed.body<PacingSettingsDto>())
            val both = putJson("/api/v1/policy/pacing", "admin-a", PacingSettingsDto(autoPoolSize = true))
            assertEquals(PacingSettingsDto(true, true), both.body())
            val other = client.get("/api/v1/policy/pacing") { bearerAuth("admin-b") }
            assertEquals(PacingSettingsDto(false, false), other.body<PacingSettingsDto>(), "Another org's settings")
        }
    }

    @Test
    fun theAdviceIsReadClaimedAndAcknowledgedWithinItsSession() = serverTest(modules, overrides) { server ->
        with(server) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/directives/current").status)
            assertEquals(HttpStatusCode.Forbidden, client.get(CURRENT) { bearerAuth("use-a") }.status)
            val none = client.get("/api/v1/directives/current") { bearerAuth("guidance-a") }
            assertEquals("no-store", none.headers[HttpHeaders.CacheControl])
            assertNull(none.body<DirectiveResponseDto>().directive)
            // Before any advice: none to preview, to read for a session, nor to claim.
            assertNull(client.get(PREVIEW) { bearerAuth("member-a") }.body<PacingPreviewDto>().directive)
            val noSession = postJson(CURRENT, "session-1", """{"sessionId":"session-1"}""")
            assertNull(noSession.body<DirectiveResponseDto>().directive)
            val noClaim = postJson(CLAIM, "session-1", """{"sessionId":"session-1","stage":"emitted_to_hook"}""")
            assertNull(noClaim.body<DirectiveClaimDto>().claimId)
            advise("")
            advise(sessionDigest("session-1"))
            val wide = client.get("/api/v1/directives/current") { bearerAuth("member-a") }.body<DirectiveResponseDto>()
            assertEquals("slow_down", wide.directive!!.pace)
            assertEquals(5_000, wide.directive!!.minIntervalMillis)
            val preview = client.get("/api/v1/directives/preview") { bearerAuth("use-a") }.body<PacingPreviewDto>()
            assertEquals(wide.directive, preview.directive)

            val scoped = postJson("/api/v1/directives/current", "session-1", """{"sessionId":"session-1"}""")
            assertNotNull(scoped.body<DirectiveResponseDto>().directive)
            val another = postJson(CURRENT, "session-1", """{"sessionId":"session-2"}""")
            assertEquals(HttpStatusCode.Forbidden, another.status)
            assertEquals(HttpStatusCode.BadRequest, postJson(CURRENT, "member-a", """{"sessionId":"a b"}""").status)

            val claimBody = """{"sessionId":"session-1","stage":"emitted_to_hook"}"""
            val claim = postJson(CLAIM, "session-1", claimBody)
            val claimed = claim.body<DirectiveClaimDto>()
            assertNotNull(claimed.directive)
            assertNull(
                postJson(CLAIM, "session-1", claimBody).body<DirectiveClaimDto>().claimId,
            )
            val badStage = postJson(CLAIM, "session-1", """{"sessionId":"session-1","stage":"x"}""")
            assertEquals(HttpStatusCode.BadRequest, badStage.status)
            val receipt = """{"id":"${claimed.directive!!.id}","stage":"emitted_to_hook",""" +
                """"sessionId":"session-1","claimId":"${claimed.claimId}"}"""
            assertEquals(HttpStatusCode.NoContent, postJson(RECEIPT, "session-1", receipt).status)
            val stolen = postJson(RECEIPT, "member-a", receipt)
            assertEquals(HttpStatusCode.Conflict, stolen.status, "Another token's claim")
            assertEquals(
                HttpStatusCode.Conflict,
                postJson(RECEIPT, "member-a", """{"id":"gone","stage":"received_by_cli"}""").status,
            )
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/directives/receipt", null, receipt).status)
            // Another org's member reads nothing of Ada's.
            assertNull(client.get(CURRENT) { bearerAuth("admin-b") }.body<DirectiveResponseDto>().directive)
            // In observation mode the agent gets nothing, the preview still shows it.
            putJson("/api/v1/policy/pacing", "admin-a", PacingSettingsDto(observationOnly = true))
            assertNull(client.get(CURRENT) { bearerAuth("member-a") }.body<DirectiveResponseDto>().directive)
            assertNotNull(client.get(PREVIEW) { bearerAuth("member-a") }.body<PacingPreviewDto>().directive)
        }
    }

    private companion object {
        const val CURRENT = "/api/v1/directives/current"
        const val PREVIEW = "/api/v1/directives/preview"
        const val CLAIM = "/api/v1/directives/claim"
        const val RECEIPT = "/api/v1/directives/receipt"
    }
}
