// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.core.receipts.ReceiptSigningKey
import run.ostrom.server.compiler.domain.models.CompileInputs
import run.ostrom.server.compiler.domain.services.CompilerDocs
import run.ostrom.server.compiler.domain.services.HarnessStamp
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.domain.services.PackageSigner
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The instance signer, with a key from a seed (deterministic, as Ed25519 is). */
internal class SeedSigner(seed: ByteArray) : InstanceSigner {
    private val key = ReceiptSigningKey.fromSeed(seed)
    override val algorithm: String = "ed25519"
    override val keyId: String = key.publicKey.kid
    override val publicKeyPem: String = key.publicKey.toSpkiPem()
    override suspend fun sign(data: ByteArray): ByteArray = key.sign(data)
    override suspend fun verify(data: ByteArray, signature: ByteArray): Boolean = key.publicKey.verify(data, signature)
}

internal fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** The golden inputs, as compile inputs. */
internal fun goldenInputs(): CompileInputs {
    val input = Json.parseToJsonElement(GoldenPackage.INPUT).jsonObject
    val docs = input.getValue("docs").jsonObject
    fun list(key: String): List<JsonObject> = docs.getValue(key).jsonArray.map { it.jsonObject }
    return CompileInputs(
        repo = input.getValue("repo").jsonPrimitive.content,
        serverUrl = input.getValue("serverUrl").jsonPrimitive.content,
        workflows = list("workflows").map(CompilerDocs::workflow),
        rules = list("rules").map(CompilerDocs::rule),
        decisions = list("decisions").map(CompilerDocs::decision),
        repos = listOf("acme-api"),
        prodApprover = null,
        codePublishers = null,
        now = Instant.parse(GoldenPackage.COMPILED_AT),
    )
}

class PackageGoldenTest {
    private val payload = Json.parseToJsonElement(GoldenPackage.PAYLOAD).jsonObject

    private suspend fun compile() = goldenInputs().let { inputs ->
        PackageCompiler.compile(inputs) { digest ->
            HarnessStamp(PackageSigner.harnessVersion(inputs.now, digest), GoldenPackage.HARNESS_RECEIPT)
        }
    }

    @Test
    fun eachFileIsTheSameBytes() = runTest {
        val expected = payload.getValue("files").jsonArray.map { it.jsonObject }
        val files = compile().files
        assertEquals(expected.map { it.getValue("path").jsonPrimitive.content }, files.map { it.path })
        expected.zip(files).forEach { (file, actual) ->
            val path = file.getValue("path").jsonPrimitive.content
            assertEquals(file.getValue("content").jsonPrimitive.content, actual.content, path)
            assertEquals(file.getValue("sha256").jsonPrimitive.content, actual.sha256)
        }
    }

    @Test
    fun eachSectionIsTheSameJson() = runTest {
        val body = compile().body
        listOf("harness", "workflows", "nodes", "hooks", "scripts", "notifications", "mcp", "context").forEach { key ->
            assertEquals(payload.getValue(key), body.getValue(key), key)
        }
    }

    @Test
    fun theSignedEnvelopeIsTheSameBytes() = runTest {
        val inputs = goldenInputs()
        val envelope = PackageSigner.sign(compile(), inputs.now, SeedSigner(hex(GoldenPackage.SEED_HEX)), pinned = true)
        // What this server signs, written before the comparison: the source to update the golden from when the
        // catalogue changes on purpose (CI uploads build/reports).
        SystemFileSystem.createDirectories(Path("build/reports/golden"))
        SystemFileSystem.sink(Path("build/reports/golden/package.json")).buffered().use {
            it.writeString(Json.encodeToString(PackageEnvelope.serializer(), envelope))
        }
        assertEquals(GoldenPackage.CONTENT_DIGEST, envelope.contentDigest)
        assertEquals(GoldenPackage.VERSION, envelope.version)
        assertEquals(GoldenPackage.PAYLOAD, envelope.payload)
        assertEquals(GoldenPackage.DIGEST, envelope.digest)
        assertEquals(GoldenPackage.KID, envelope.kid)
        assertEquals(GoldenPackage.SIGNATURE, envelope.signature)
        assertEquals("acme-api", envelope.repo)
        assertEquals("ostrom.package/1", envelope.format)
    }
}
