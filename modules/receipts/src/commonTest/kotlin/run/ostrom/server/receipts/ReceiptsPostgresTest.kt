// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.signing.InstanceSigner
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.receipts.presentation.ReceiptKeysResponse
import run.ostrom.server.receipts.presentation.ReceiptsPage
import run.ostrom.server.receipts.presentation.VerificationResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReceiptsPostgresTest {
    // A token ends with its organization's letter: `a`, `b`, `c` (a person's session), `agent-a`, `device-a`,
    // `reader-a` (receipts:read only), `profile-a` (profile:read only); any other belongs to no organization.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore('-', "")) {
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf("agent:session"))
                    "device" -> AuthenticatedPrincipal(token, PrincipalKind.DEVICE, setOf("user:*"))
                    "reader" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("receipts:read"))
                    "profile" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("profile:read"))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
                }
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    when (principal.subjectId.substringAfterLast('-')) {
                        "a" -> OrgPrincipal(ORG_A, Uuid.random(), OrgRole.MEMBER)
                        "b" -> OrgPrincipal(ORG_B, Uuid.random(), OrgRole.MEMBER)
                        "c" -> OrgPrincipal(ORG_C, Uuid.random(), OrgRole.MEMBER)
                        else -> null
                    }
            }
        }
    }

    private fun receiptsTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(InstanceModule, ReceiptsModule), overrides) { server -> block(server) }

    private suspend fun TestServer.call(token: String?, path: String): HttpResponse = client.get(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun TestServer.sign(org: Uuid, subjectId: String, kind: String = "gate"): JsonObject =
        koin.get<SignReceiptUseCase>()(org, draft(subjectId, kind))

    @Test
    fun aSignedReceiptIsServedAsSignedAndVerifies() = receiptsTest { server ->
        val receipt = server.sign(ORG_A, "gate-1")
        val id = receipt.text("id")
        assertEquals(receipt, server.call("a", "/api/v1/receipts/$id").body<JsonObject>())

        val verification = server.call("a", "/api/v1/receipts/$id/verify").body<VerificationResponse>()
        assertTrue(verification.ok)
        val signer = server.koin.get<InstanceSigner>()
        assertEquals(signer.keyId, verification.kid)
        assertNull(verification.reason)

        val keys = server.call(null, "/api/v1/receipts/keys").body<ReceiptKeysResponse>()
        assertEquals(listOf(signer.keyId), keys.keys.map { it.kid })
        assertEquals("ed25519", keys.keys.single().alg)
        assertEquals(signer.publicKeyPem, keys.keys.single().publicKeyPem)
    }

    @Test
    fun receiptsAreListedNewestFirstByPage() = receiptsTest { server ->
        val ids = (1..5).map { server.sign(ORG_A, "gate-$it").text("id") }
        server.sign(ORG_A, "s-1", kind = "session")

        server.sign(ORG_B, "gate-1")

        val first = server.call("a", "/api/v1/receipts?subjectKind=gate&limit=2").body<ReceiptsPage>()
        assertEquals(ids.reversed().take(2), first.items.map { it.text("id") })
        val second = server.call("a", "/api/v1/receipts?subjectKind=gate&limit=2&before=${first.next}")
            .body<ReceiptsPage>()
        assertEquals(ids.reversed().drop(2).take(2), second.items.map { it.text("id") })

        val one = server.call("a", "/api/v1/receipts?subjectKind=gate&subjectId=gate-3").body<ReceiptsPage>()
        assertEquals(listOf(ids[2]), one.items.map { it.text("id") })
        assertNull(one.next, "A page that is not full has no next one")
        // TeamCode #238: a member known by their organization alone (no gates module, no login) reads no session's.
        val all = server.call("a", "/api/v1/receipts").body<ReceiptsPage>()
        assertEquals(ids.reversed(), all.items.map { it.text("id") })
    }

    @Test
    fun s247ReceiptsAreReadByAPersonWithTheReadScopeOnly() = receiptsTest { server ->
        val id = server.sign(ORG_A, "gate-1").text("id")
        val paths = listOf("/api/v1/receipts", "/api/v1/receipts/$id", "/api/v1/receipts/$id/verify")
        for (path in paths) {
            assertEquals(HttpStatusCode.Forbidden, server.call("agent-a", path).status, "an agent token: $path")
            assertEquals(HttpStatusCode.Forbidden, server.call("device-a", path).status, "a device: $path")
            assertEquals(HttpStatusCode.Forbidden, server.call("profile-a", path).status, "no receipts scope: $path")
            assertEquals(HttpStatusCode.OK, server.call("reader-a", path).status, "receipts:read: $path")
        }
        assertEquals(HttpStatusCode.OK, server.call(null, "/api/v1/receipts/keys").status, "the keys stay public")
    }

    @Test
    fun anotherOrganizationSeesNothing() = receiptsTest { server ->
        val id = server.sign(ORG_A, "gate-1").text("id")
        assertEquals(HttpStatusCode.NotFound, server.call("b", "/api/v1/receipts/$id").status)
        assertEquals(HttpStatusCode.NotFound, server.call("b", "/api/v1/receipts/$id/verify").status)
        assertTrue(server.call("b", "/api/v1/receipts").body<ReceiptsPage>().items.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/receipts").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/receipts/$id").status)
        assertEquals(HttpStatusCode.NotFound, server.call("nobody", "/api/v1/receipts").status)
        assertEquals(HttpStatusCode.NotFound, server.call("a", "/api/v1/receipts/rcpt_unknown").status)
    }

    @Test
    fun theDatabaseItselfKeepsOrganizationsApart() = receiptsTest { server ->
        server.sign(ORG_A, "gate-1")
        val counts = server.driver.transaction {
            val none = fetchAll("SELECT COUNT(*) AS n FROM receipts_receipts").getOrThrow().first().get("n").asString()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", ORG_B.toString()))
                .getOrThrow()
            val other = fetchAll("SELECT COUNT(*) AS n FROM receipts_receipts").getOrThrow().first().get("n").asString()
            none to other
        }
        assertEquals("0" to "0", counts, "Without the organization, and from another one, no row is visible")
        // PostgreSQL refuses the row (WITH CHECK): the statement fails, and with it the transaction.
        val refused = assertFailsWith<SQLError> {
            server.driver.transaction {
                val setOrg = Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)")
                fetchAll(setOrg.bind("org", ORG_B.toString())).getOrThrow()
                execute(
                    Statement.create(
                        "INSERT INTO receipts_receipts (org_id, id, subject_kind, subject_id, body, created_at) " +
                            "VALUES (:org, 'rcpt_x', 'gate', 'g', '{}', now())",
                    ).bind("org", ORG_A),
                ).getOrThrow()
            }
        }
        assertTrue("row-level security" in refused.message.orEmpty(), "A row of another organization is refused")
    }

    @Test
    fun twoSignersOfTheSameIdKeepOneReceipt() = receiptsTest { server ->
        val repository = server.koin.get<ReceiptsRepository>()
        val at = Instant.parse("2026-10-30T09:00:00Z")
        val stored = coroutineScope {
            (1..4).map { n ->
                async {
                    repository.insertIfAbsent(
                        StoredReceipt(ORG_A, "rcpt_same", "gate", "g", JsonObject(draft("g-$n")), at),
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, stored.map { it.body }.distinct().size)
        assertEquals(stored.first(), repository.get(ORG_A, "rcpt_same"))
        assertNull(repository.get(ORG_B, "rcpt_same"))
    }

    @Test
    fun anIdTwoOrganizationsShareReadsAsEachOnesOwnReceiptAndAThirdFindsNone() = receiptsTest { server ->
        // A harness receipt id derives from the harness version and digest (`rcpt_harness_<version>`): two
        // organizations that compile the same sources in the same minute sign under the same id.
        val id = "rcpt_harness_h202610301200-ca824dbe"
        val sign = server.koin.get<SignReceiptUseCase>()
        val ours = sign(ORG_A, draft("h202610301200-ca824dbe@a", "harness"), id)
        val theirs = sign(ORG_B, draft("h202610301200-ca824dbe@b", "harness"), id)
        assertNotEquals(ours, theirs)

        assertEquals(ours, server.call("a", "/api/v1/receipts/$id").body<JsonObject>())
        assertEquals(theirs, server.call("b", "/api/v1/receipts/$id").body<JsonObject>())
        val signer = server.koin.get<InstanceSigner>()
        val checks = listOf("a", "b").map {
            server.call(it, "/api/v1/receipts/$id/verify").body<VerificationResponse>()
        }
        assertTrue(checks.all { it.ok && it.kid == signer.keyId }, "each verifies under the instance key: $checks")
        assertNotEquals(checks[0].digest, checks[1].digest, "each verifies its own body")
        assertEquals(HttpStatusCode.NotFound, server.call("c", "/api/v1/receipts/$id").status)
        assertEquals(HttpStatusCode.NotFound, server.call("c", "/api/v1/receipts/$id/verify").status)

        // The bundle (`/receipts/{id}/bundle`, workflow-runs) reads the receipt and its chain through this get.
        val repository = server.koin.get<ReceiptsRepository>()
        assertEquals(ours, repository.get(ORG_A, id)?.body)
        assertEquals(theirs, repository.get(ORG_B, id)?.body)
        assertNull(repository.get(ORG_C, id))
    }

    private companion object {
        val ORG_C: Uuid = Uuid.parse("0000000c-0000-0000-0000-000000000000")
    }
}
