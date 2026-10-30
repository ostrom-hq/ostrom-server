package dev.ostrom.server.compiler

import dev.ostrom.core.receipts.ReceiptSigningKey
import dev.ostrom.server.compiler.domain.models.CompileInputs
import dev.ostrom.server.compiler.domain.services.CompilerDocs
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.compiler.domain.services.PackageSigner
import dev.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.coroutines.test.runTest
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
