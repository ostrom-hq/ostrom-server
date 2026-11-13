// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * Audit A6: a push the hook refused on the workstation without asking (git redirected, a push it cannot read) is
 * reported after the fact and written to the organization's audit log as `gate.denied_locally`, its command masked
 * again. The session is checked as a gate's ask checks it: unknown or another organization's 404, closed 409, another
 * launch's refused; a refused report writes nothing.
 */
class LocalDenialsPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val audit = mutableListOf<OrgAuditRecord>()

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:dana`, `agent:a:launch-1` (`agent:a:none` has no launch),
    // `reader:a:x` holds `gates:read` only.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ASK))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf(GateScopes.AGENT_SESSION))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.READ))
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor {
                    val (kind, org, id) = principal.subjectId.split(":")
                    val orgId = if (org == "a") orgA else orgB
                    return if (kind == "agent") {
                        val launch = id.takeIf { it != "none" }
                        GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = launch, personId = "dana")
                    } else {
                        GateActor.person(id, orgId, OrgRole.MEMBER)
                    }
                }
            }
        }
        // Organization A has `s-open`, of the launch `launch-1`, and `s-closed`; B has none.
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) {
                    if (orgId != orgA || sessionId !in setOf(OPEN, CLOSED)) {
                        throw OstromException(ErrorKind.NOT_FOUND, "Session not found.")
                    }
                    if (launchId != null && launchId != "launch-1") {
                        throw OstromException(ErrorKind.FORBIDDEN, "This session is not yours.")
                    }
                    if (sessionId == CLOSED) throw OstromException(ErrorKind.CONFLICT, "session closed")
                }

                override suspend fun agentOf(orgId: Uuid, sessionId: String): String? = "claude-code"
            }
        }
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
        // The receipts module is not loaded: the routes' signer is never called here.
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    private fun deniedTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule), overrides) { server -> block(server) }

    private suspend fun TestServer.report(token: String?, body: String): HttpResponse =
        client.post("/api/v1/gates/local-denials") {
            if (token != null) bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun denial(
        session: String = OPEN,
        cls: String = "push-main",
        reason: String = "redirected",
        command: String = "git --git-dir=../other/.git push origin main",
        repo: String? = "acme/api",
    ): String {
        val repoField = if (repo == null) "" else """, "repo": "$repo""""
        return """{"sessionId": "$session", "class": "$cls", "reason": "$reason", "command": "$command"$repoField}"""
    }

    @Test
    fun aRefusalIsWrittenToTheOrganizationsAuditLogWithoutItsSecrets() = deniedTest { server ->
        val secret = "ghp_0123456789abcdefghijABCDEFGHIJ"
        val command = "git --git-dir=../other/.git push https://x:$secret@github.com/acme/api.git main"

        assertEquals(HttpStatusCode.Accepted, server.report("user:a:dana", denial(command = command)).status)

        val line = audit.single()
        assertEquals(orgA, line.orgId)
        assertEquals("user:a:dana", line.actor.subjectId)
        assertEquals("gate.denied_locally", line.event)
        assertEquals("session", line.subjectType)
        assertEquals(OPEN, line.subjectId)
        val masked = "git --git-dir=../other/.git push https://***@github.com/acme/api.git main"
        val details = mapOf(
            "session" to OPEN,
            "class" to "push-main",
            "reason" to "redirected",
            "command" to masked,
            "repo" to "acme/api",
        )
        assertEquals(details, line.details)
        assertFalse(secret in audit.toString(), "the secret is never in the audit log")
    }

    @Test
    fun anAgentReportsOnlyInItsOwnLaunchsSessions() = deniedTest { server ->
        val unreadable = denial(reason = "unreadable", command = "git push", repo = null)
        assertEquals(HttpStatusCode.Accepted, server.report("agent:a:launch-1", unreadable).status)
        val line = audit.single()
        assertEquals("unreadable", line.details["reason"])
        assertEquals(null, line.details["repo"], "no repository named, none written")

        // Audit A12: another launch's agent, or an agent token without a launch, speaks of no session.
        assertEquals(HttpStatusCode.Forbidden, server.report("agent:a:launch-2", denial()).status)
        assertEquals(HttpStatusCode.Forbidden, server.report("agent:a:none", denial()).status)
        assertEquals(1, audit.size)
    }

    @Test
    fun everyReasonOfTheContractIsRecorded() = deniedTest { server ->
        val reasons = listOf(
            "redirected",
            "unreadable",
            "unknown_landing",
            "dynamic_command",
            "git_setting",
            "foreign_repository",
            "policy_held",
        )
        for (reason in reasons) {
            assertEquals(HttpStatusCode.Accepted, server.report("user:a:dana", denial(reason = reason)).status, reason)
        }
        assertEquals(reasons, audit.map { it.details["reason"] })
        assertEquals(HttpStatusCode.BadRequest, server.report("user:a:dana", denial(reason = "Redirected")).status)
        assertEquals(reasons.size, audit.size)
    }

    @Test
    fun anUnknownClosedOrOtherOrganizationsSessionWritesNothing() = deniedTest { server ->
        assertEquals(HttpStatusCode.NotFound, server.report("user:a:dana", denial(session = "s-gone")).status)
        assertEquals(HttpStatusCode.NotFound, server.report("user:b:zoe", denial()).status)
        assertEquals(HttpStatusCode.Conflict, server.report("user:a:dana", denial(session = CLOSED)).status)
        assertEquals(emptyList(), audit)
    }

    @Test
    fun anOutOfBoundsReportIsABadRequestAndWritesNothing() = deniedTest { server ->
        val refused = listOf(
            denial(session = " "),
            denial(session = "s".repeat(129)),
            denial(cls = ""),
            denial(cls = "c".repeat(201)),
            denial(reason = "blocked"),
            denial(command = "x".repeat(4_097)),
            denial(repo = "r".repeat(201)),
        )
        for (body in refused) {
            assertEquals(HttpStatusCode.BadRequest, server.report("user:a:dana", body).status, body.take(80))
        }
        // At the bounds, a report is recorded.
        val longest = denial(session = OPEN, command = "x".repeat(4_096), repo = "r".repeat(200))
        assertEquals(HttpStatusCode.Accepted, server.report("user:a:dana", longest).status)
        assertEquals(1, audit.size)
    }

    @Test
    fun aCallerThatMayNotAskAGateIsRefused() = deniedTest { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.report(null, denial()).status)
        assertEquals(HttpStatusCode.Forbidden, server.report("reader:a:x", denial()).status)
        assertEquals(emptyList(), audit)
    }

    private companion object {
        const val OPEN = "s-open"
        const val CLOSED = "s-closed"
    }
}
