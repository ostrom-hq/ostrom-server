// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.compiler.domain.models.CatalogEntry
import dev.ostrom.server.compiler.domain.usecases.GatePolicyUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** SECURITY-AUDIT S2-20: the guarded actions the hooks trust are the server's, signed, for minutes, fail-closed. */
class GatePolicyUseCaseTest {
    private val signer = SeedSigner(ByteArray(32) { it.toByte() })
    private val org = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val asked = mutableListOf<Pair<Uuid, String?>>()
    private val catalog = object : GatesCatalogUseCase {
        override suspend fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry> {
            asked += orgId to repo
            return emptyList()
        }
    }

    @Test
    fun s220ThePolicyIsTheCatalogueSignedByTheInstanceForTenMinutesFailClosed() = runTest {
        val envelope = GatePolicyUseCaseImpl(catalog, signer, SettableClock())(org, " acme-api ")
        assertEquals("ostrom.gates/1", envelope.getValue("format").jsonPrimitive.content)
        assertEquals("ed25519", envelope.getValue("alg").jsonPrimitive.content)
        assertEquals(signer.keyId, envelope.getValue("kid").jsonPrimitive.content)
        val payload = envelope.getValue("payload").jsonPrimitive.content
        val signature = Base64.decode(envelope.getValue("signature").jsonPrimitive.content)
        assertTrue(signer.verify(payload.encodeToByteArray(), signature), "signed over the payload's bytes")
        val body = Json.parseToJsonElement(payload).jsonObject
        assertEquals("acme-api", body.getValue("repo").jsonPrimitive.content)
        assertEquals(NOW.toString(), body.getValue("issuedAt").jsonPrimitive.content)
        assertEquals("2026-10-30T08:55:00Z", body.getValue("expiresAt").jsonPrimitive.content)
        assertEquals("closed", body.getValue("failMode").jsonPrimitive.content)
        assertTrue(body.getValue("actions").jsonArray.isEmpty() && body.getValue("gates").jsonArray.isEmpty())
        val expected: List<Pair<Uuid, String?>> = listOf(org to "acme-api")
        assertEquals(expected, asked.toList(), "the organization's catalogue for that repository")
    }

    @Test
    fun aPolicyNamesItsRepository() = runTest {
        for (repo in listOf(null, " ", "r".repeat(201))) {
            val policy = GatePolicyUseCaseImpl(catalog, signer, SettableClock())
            val refused = assertFailsWith<OstromException> { policy(org, repo) }
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
        }
        assertTrue(asked.isEmpty())
    }
}
