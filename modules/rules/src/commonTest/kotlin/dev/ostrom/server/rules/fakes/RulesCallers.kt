// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.fakes

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.MemberRole
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.testing.TestServer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The people of the server tests, each with a bearer token equal to its name. A browser session holds every user
 * scope (`user:*`); the others hold what their name says.
 */
object RulesCallers {
    val orgA: Uuid = RulesFixtures.orgA
    val orgB: Uuid = RulesFixtures.orgB
    private val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    private val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    private val ownerB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)

    // token -> (caller, person, kind, scopes)
    private val people = mapOf(
        "admin-a" to Person(adminA, "lyes", PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
        "nameless-a" to Person(adminA, null, PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
        "member-a" to Person(memberA, "mia", PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
        "owner-b" to Person(ownerB, "bob", PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
        "reader-a" to Person(adminA, "lyes", PrincipalKind.USER, setOf("rules:read")),
        "gates-reader-a" to Person(memberA, "mia", PrincipalKind.USER, setOf("gates:read")),
        "other-a" to Person(adminA, "lyes", PrincipalKind.USER, setOf(RelayScopes.MCP_READ)),
        "agent-a" to Person(memberA, "lyes", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
        "device-a" to Person(adminA, "lyes", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
        "stranger" to Person(null, null, PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
    )

    private data class Person(val caller: Caller?, val login: String?, val kind: PrincipalKind, val scopes: Set<String>)

    /**
     * The fake authentication, callers and gate actors, and the clock.
     *
     * @param clock The clock the server reads.
     */
    fun overrides(clock: Clock = TestClock()): Module = module {
        single<Clock> { clock }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
                    people[token]?.let { AuthenticatedPrincipal(token, it.kind, it.scopes) }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    people[principal.subjectId]?.caller
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val person = people[principal.subjectId]
                    val caller = person?.caller
                    val login = person?.login
                    return if (person == null || caller == null || login == null) {
                        null
                    } else {
                        actorOf(person.kind, caller.orgId, login)
                    }
                }
            }
        }
    }

    /** The gates' other ports: open sessions, one sole approver (lyes) on pushes to main, receipts by id. */
    val gates: Module = module {
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) = Unit
            }
        }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes"), enforced = true)
                        .takeIf { actionClass == "push-main" }
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }
}

/**
 * Calls a route as [token] (none when null), with a JSON body when one is given.
 *
 * @param method `GET`, `POST`, `PUT`, `PATCH` or `DELETE`.
 * @param path The path.
 * @param token The bearer token.
 * @param body The body, sent as JSON.
 * @return The response.
 */
suspend fun TestServer.call(method: String, path: String, token: String?, body: Any? = null): HttpResponse {
    val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        token?.let { bearerAuth(it) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    return when (method) {
        "GET" -> client.get(path, request)
        "POST" -> client.post(path, request)
        "PUT" -> client.put(path, request)
        "PATCH" -> client.patch(path, request)
        else -> client.delete(path, request)
    }
}

private fun actorOf(kind: PrincipalKind, orgId: Uuid, login: String): GateActor = when (kind) {
    PrincipalKind.SERVICE -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = "launch-1", personId = login)
    PrincipalKind.DEVICE -> GateActor(login, orgId, ActorKind.DEVICE, personId = login)
    else -> GateActor(login, orgId, ActorKind.USER, personId = login)
}
