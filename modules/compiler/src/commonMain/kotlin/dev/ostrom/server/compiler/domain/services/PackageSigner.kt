// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.services

import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.receipts.CanonicalJson
import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/** Versions, payload and signature of a session package, as the prototype (`compiler/compile.ts` `publish`). */
object PackageSigner {
    private const val STAMP_LENGTH = 12
    private const val SHORT_DIGEST = 8

    /**
     * The minute of a compilation, `yyyyMMddHHmm` in UTC.
     *
     * @param at The time.
     * @return The stamp.
     */
    fun stamp(at: Instant): String = JsText.iso(at).filter { it.isDigit() }.take(STAMP_LENGTH)

    /**
     * A harness version: `h<stamp>-<digest8>`.
     *
     * @param at The compilation time.
     * @param digest The harness digest.
     * @return The version.
     */
    fun harnessVersion(at: Instant, digest: String): String = "h${stamp(at)}-${digest.take(SHORT_DIGEST)}"

    /**
     * A package version: `p<stamp>-<contentDigest8>`.
     *
     * @param at The compilation time.
     * @param contentDigest The package's content digest.
     * @return The version.
     */
    fun packageVersion(at: Instant, contentDigest: String): String = "p${stamp(at)}-${contentDigest.take(SHORT_DIGEST)}"

    /**
     * Signs a compiled body: the payload is the canonical form of the body with its version, content digest and
     * compilation time; the envelope carries the payload's digest and its Ed25519 signature.
     *
     * @param body The compiled body.
     * @param at The compilation time.
     * @param signer The instance key.
     * @param pinned True when the package is the repository's pinned one.
     * @return The envelope, served as is.
     */
    suspend fun sign(body: CompiledBody, at: Instant, signer: InstanceSigner, pinned: Boolean): PackageEnvelope {
        val contentDigest = body.contentDigest
        val version = packageVersion(at, contentDigest)
        val payload = CanonicalJson.encode(
            JsonObject(
                body.body + mapOf(
                    "version" to JsonPrimitive(version),
                    "contentDigest" to JsonPrimitive(contentDigest),
                    "compiledAt" to JsonPrimitive(JsText.iso(at)),
                ),
            ),
        )
        val bytes = payload.encodeToByteArray()
        return PackageEnvelope(
            format = PackageCompiler.FORMAT,
            version = version,
            repo = body.repo,
            digest = Digests.sha256Hex(bytes),
            contentDigest = contentDigest,
            alg = signer.algorithm,
            kid = signer.keyId,
            signature = Base64.encode(signer.sign(bytes)),
            payload = payload,
            pinned = pinned,
            revoked = false,
        )
    }
}
