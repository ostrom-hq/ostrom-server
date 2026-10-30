// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.receipts.domain.models.StoredReceipt
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.receipts.presentation.ReceiptKeysResponse
import dev.ostrom.server.receipts.presentation.ReceiptsPage
import dev.ostrom.server.receipts.presentation.VerificationResponse
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ReceiptsPostgresTest {
    // A token is the organization's letter: `a`, `b`; any other token belongs to no organization.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) =
                    AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet())
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    when (principal.subjectId) {
                        "a" -> OrgPrincipal(ORG_A, Uuid.random(), OrgRole.MEMBER)
                        "b" -> OrgPrincipal(ORG_B, Uuid.random(), OrgRole.MEMBER)
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
        assertEquals(6, server.call("a", "/api/v1/receipts").body<ReceiptsPage>().items.size)
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
        server.driver.execute(
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
        ).getOrThrow()
        server.driver.execute("GRANT SELECT, INSERT ON receipts_receipts TO ostrom_rls_probe").getOrThrow()
        val counts = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            val none = fetchAll("SELECT COUNT(*) AS n FROM receipts_receipts").getOrThrow().first().get("n").asString()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", ORG_B.toString()))
                .getOrThrow()
            val other = fetchAll("SELECT COUNT(*) AS n FROM receipts_receipts").getOrThrow().first().get("n").asString()
            none to other
        }
        assertEquals("0" to "0", counts, "Without the organization, and from another one, no row is visible")
        val write = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", ORG_B.toString()))
                .getOrThrow()
            execute(
                Statement.create(
                    "INSERT INTO receipts_receipts (org_id, id, subject_kind, subject_id, body, created_at) " +
                        "VALUES (:org, 'rcpt_x', 'gate', 'g', '{}', now())",
                ).bind("org", ORG_A),
            )
        }
        assertTrue(write.isFailure, "A row of another organization cannot be written")
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
}
