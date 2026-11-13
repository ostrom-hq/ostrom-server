// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts

import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.services.ReceiptBodySigner
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The verification route says whether a receipt is bound to the caller's organization (`Verification.orgBound`).
class ReceiptOrgBindingPostgresTest {
    // The token `a` is a person of A, `b` a person of B.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) =
                    AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
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

    private suspend fun TestServer.verify(token: String, id: String): JsonObject =
        Json.parseToJsonElement(
            client.get("/api/v1/receipts/$id/verify") { header(HttpHeaders.Authorization, "Bearer $token") }
                .bodyAsText(),
        ).jsonObject

    private fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull

    @Test
    fun aReceiptSignedForTheCallersOrganizationIsBound() = receiptsTest { server ->
        val id = server.koin.get<SignReceiptUseCase>()(ORG_A, draft("gate-bound")).text("id")
        val outcome = server.verify("a", id)
        assertEquals(true, outcome.flag("ok"))
        assertEquals(true, outcome.flag("orgBound"), "It names A, who verifies it")
    }

    @Test
    fun aReceiptSignedBeforeTheOrganizationStaysVerifiableButUnbound() = receiptsTest { server ->
        val body = JsonObject(draft("gate-old") + mapOf("id" to JsonPrimitive(OLD), "createdAt" to JsonPrimitive(AT)))
        val signed = server.koin.get<ReceiptBodySigner>().sign(body)
        server.koin.get<ReceiptsRepository>()
            .insertIfAbsent(StoredReceipt(ORG_A, OLD, "gate", "gate-old", signed, Instant.parse(AT)))

        val outcome = server.verify("a", OLD)
        assertEquals(true, outcome.flag("ok"), "Its signature still holds")
        assertEquals(false, outcome.flag("orgBound"), "Never shown as bound to an organization")
    }

    @Test
    fun aReceiptOfAnotherOrganizationIsRefused() = receiptsTest { server ->
        val ofA = server.koin.get<SignReceiptUseCase>()(ORG_A, draft("gate-1"))
        val id = ofA.text("id")
        // A's receipt, byte for byte, in B's hands.
        server.koin.get<ReceiptsRepository>()
            .insertIfAbsent(StoredReceipt(ORG_B, id, "gate", "gate-1", ofA, Instant.parse(AT)))

        val outcome = server.verify("b", id)
        assertEquals(false, outcome.flag("ok"), "A's receipt is not B's")
        assertEquals(false, outcome.flag("orgBound"))
        assertEquals("This receipt was signed for another organization.", outcome.text("reason"))
        assertEquals(true, server.verify("a", id).flag("ok"), "It stays A's")
    }

    private companion object {
        const val OLD = "rcpt_signed_before_the_org"
        const val AT = "2026-10-30T09:12:34.567Z"
    }
}
