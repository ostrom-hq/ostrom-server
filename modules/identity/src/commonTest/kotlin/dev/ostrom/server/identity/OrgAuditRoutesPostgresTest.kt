// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ORG_AUDIT_PAGE
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.domain.auditCanonicalForm
import dev.ostrom.server.identity.presentation.CreateTokenRequest
import dev.ostrom.server.identity.presentation.CreatedTokenResponse
import dev.ostrom.server.identity.presentation.OrgAuditEventResponse
import dev.ostrom.server.identity.presentation.OrgAuditPageResponse
import dev.ostrom.server.identity.presentation.SERVER_ACTOR
import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgProfile
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/audit` through the identity module on a real PostgreSQL, the organizations given by a resolver of the
 * test (the orgs module's own is proven in its `OrgAuditPostgresTest`): every kind of actor, the filters and the
 * pages, read under the organization's row-level security; a plain member, a person outside and a token without
 * `audit:read` are refused.
 */
class OrgAuditRoutesPostgresTest {
    private val acme = Uuid.random()
    private val evil = Uuid.random()
    private val memberships = mutableMapOf<String, OrgPrincipal>()
    private val overrides = module {
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    memberships[principal.subjectId]

                override suspend fun profile(orgId: Uuid): OrgProfile? = null
            }
        }
    }

    /** Signs a person in; with [org], makes them a member of it with [role]. */
    private suspend fun TestServer.signIn(
        login: String,
        org: Uuid? = null,
        role: OrgRole = OrgRole.OWNER,
    ): Pair<User, StartedSession> {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        org?.let { memberships["${user.id}"] = OrgPrincipal(it, Uuid.random(), role) }
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    private suspend fun TestServer.audit(session: StartedSession, query: String = ""): HttpResponse =
        client.get("/api/v1/audit$query") { browserSession(session.secret, null) }

    private suspend fun TestServer.page(session: StartedSession, query: String = ""): OrgAuditPageResponse =
        audit(session, query).body()

    @Test
    fun anOwnerReadsEveryKindOfActorFilteredAndByPages() = serverTest(listOf(IdentityModule), overrides) { server ->
        with(server) {
            val (ada, session) = signIn("ada", acme)
            val gone = Uuid.random()
            val log = koin.get<AuditLog>()
            log.record(AuditEntry(acme, null, "org.purged", "org", "$acme"))
            log.record(AuditEntry(acme, gone, "member.removed", "member", "m1"))
            val key = AuthenticatedPrincipal("key-1", PrincipalKind.SERVICE, emptySet())
            koin.get<OrgAuditTrail>().record(OrgAuditRecord(acme, key, "budget.changed", "budget", "organization"))
            log.record(AuditEntry(acme, ada.id, "org.renamed", "org", "$acme"))
            log.record(AuditEntry(evil, ada.id, "org.created", "org", "$evil"))

            val all = page(session)
            assertEquals(listOf("ada", "service:key-1", "$gone", SERVER_ACTOR), all.items.map { it.actor })
            assertEquals(listOf("org:$acme", "budget:organization"), all.items.take(2).map { it.subject })
            assertEquals("api_token", all.items[1].meta["credential"])
            assertNull(all.next)
            assertEquals(listOf("org.renamed"), page(session, "?actor=ada").items.map { it.event })
            assertEquals(listOf("member.removed"), page(session, "?event=member.removed").items.map { it.event })
            assertEquals(listOf("org.purged"), page(session, "?before=2").items.map { it.event })
            assertEquals(emptyList(), page(session, "?actor=ada&event=org.purged").items)
            assertEquals(HttpStatusCode.BadRequest, audit(session, "?before=soon").status)

            repeat(ORG_AUDIT_PAGE) { log.record(AuditEntry(acme, null, "test.line", "test", "$it")) }
            val first = page(session)
            assertEquals(ORG_AUDIT_PAGE, first.items.size)
            val next = assertNotNull(first.next)
            val rest = page(session, "?before=$next")
            val older = listOf("org.renamed", "budget.changed", "member.removed", "org.purged")
            assertEquals(older, rest.items.map { it.event })
            assertNull(rest.next)
        }
    }

    @Test
    fun onlyAnOwnerOrAnAdminReadsAndOnlyWithTheScope() = serverTest(listOf(IdentityModule), overrides) { server ->
        with(server) {
            val (_, owner) = signIn("ada", acme)
            koin.get<AuditLog>().record(AuditEntry(evil, null, "org.created", "org", "$evil"))
            val (_, admin) = signIn("carol", acme, OrgRole.ADMIN)
            assertEquals(emptyList(), page(admin).items, "Another organization's line is never read")
            val (_, member) = signIn("bob", acme, OrgRole.MEMBER)
            assertEquals(HttpStatusCode.Forbidden, audit(member).status)
            val (_, outsider) = signIn("mallory")
            assertEquals(HttpStatusCode.NotFound, audit(outsider).status)

            val narrow = token(owner, "profile:read")
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/audit") { bearerAuth(narrow) }.status)
            val reader = token(owner, "audit:read")
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/audit") { bearerAuth(reader) }.status)
        }
    }

    /** Changes the table as only its owner or a superuser can, the append-only triggers set aside. */
    private suspend fun TestServer.tamper(sql: String) = database.superuser.transaction {
        execute("SET LOCAL session_replication_role = replica").getOrThrow()
        execute(sql).getOrThrow()
    }

    /** Checks [line] as a client would: the canonical form rebuilt from the line, its hash and its signature. */
    private suspend fun TestServer.checks(org: Uuid, line: OrgAuditEventResponse): Boolean {
        val (type, target) = line.subject.split(":", limit = 2)
        val entry = AuditEntry(org, line.actorId?.let(Uuid::parse), line.event, type, target, line.meta)
        val at = Instant.parse(line.at)
        val canonical = auditCanonicalForm(Uuid.parse(line.id), line.sequence, entry, at, line.previousHash)
        val signer = koin.get<InstanceSigner>()
        val signed = signer.verify(canonical.encodeToByteArray(), Base64.decode(line.signature))
        return koin.get<SecretHasher>().hash(canonical) == line.hash && signed && line.keyId == signer.keyId
    }

    @Test
    fun everyLineIsCheckedAsItIsReadAndATamperedTableIsSignalled() =
        serverTest(listOf(IdentityModule), overrides) { server ->
        with(server) {
            val (ada, session) = signIn("ada", acme)
            val log = koin.get<AuditLog>()
            log.record(AuditEntry(acme, ada.id, "org.renamed", "org", "$acme", mapOf("to" to "Acme: Labs")))
            log.record(AuditEntry(acme, null, "org.purged", "org", "$acme"))
            repeat(3) { log.record(AuditEntry(acme, ada.id, "budget.changed", "budget", "b$it")) }
            // The same person and the same event in another organization: no filter brings them in (A6).
            log.record(AuditEntry(evil, ada.id, "budget.changed", "budget", "evil"))

            val clean = page(session)
            assertEquals(List(5) { "verified" }, clean.items.map { it.integrity })
            assertTrue(clean.items.all { checks(acme, it) }, "A client checks every line itself")
            assertEquals(clean.items.drop(1).map { it.hash }, clean.items.dropLast(1).map { it.previousHash })
            assertNull(clean.items.last().previousHash)
            assertEquals(listOf(null, "${ada.id}"), listOf(clean.items[3].actorId, clean.items[4].actorId))
            // Written as null, never left out: a client rebuilds the canonical form from them.
            val purged = audit(session, "?event=org.purged").bodyAsText()
            assertTrue("\"actorId\":null" in purged, purged)
            val first = audit(session, "?event=org.renamed").bodyAsText()
            assertTrue("\"previousHash\":null" in first, first)
            val filtered = page(session, "?actor=ada&event=budget.changed")
            assertEquals(listOf("budget:b2", "budget:b1", "budget:b0"), filtered.items.map { it.subject })

            // Line 2 rewritten in place, then line 3 erased.
            tamper("UPDATE identity_audit_events SET action = 'org.kept' WHERE chain = '$acme' AND sequence = 2")
            tamper("DELETE FROM identity_audit_events WHERE chain = '$acme' AND sequence = 3")

            val read = page(session).items.associate { it.sequence to it.integrity }
            assertEquals(mapOf(5L to "verified", 4L to "unlinked", 2L to "altered", 1L to "verified"), read)
            // The line before is read even when the filter leaves it out.
            val byEvent = page(session, "?event=budget.changed").items.associate { it.sequence to it.integrity }
            assertEquals(mapOf(5L to "verified", 4L to "unlinked"), byEvent)
        }
    }

    private suspend fun TestServer.token(session: StartedSession, scope: String): String =
        client.post("/api/v1/tokens") {
            browserSession(session.secret, session.csrfToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTokenRequest("audit", listOf(scope)))
        }.body<CreatedTokenResponse>().secret
}
