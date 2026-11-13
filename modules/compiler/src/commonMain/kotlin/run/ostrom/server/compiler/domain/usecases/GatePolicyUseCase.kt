// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.usecases

import run.ostrom.server.compiler.domain.models.CatalogEntry
import run.ostrom.server.compiler.domain.services.RulesHarness
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The organization's gate policy for a repository, signed by the instance (`ostrom.gates/1`, SECURITY-AUDIT S2-20): the
 * hooks trust only this list of guarded actions, checked against the instance's published keys, for a few minutes; a
 * file on the machine may add guarded actions, never remove one, and without a valid policy every action is held.
 */
interface GatePolicyUseCase {
    /**
     * Signs the policy.
     *
     * @param orgId The organization.
     * @param repo The session's repository.
     * @return `{format, alg, kid, signature, payload}`, the signature (base64) over the payload's UTF-8 bytes, the
     *   payload `{repo, issuedAt, expiresAt, failMode, waitSeconds, gates, actions}`.
     * @throws OstromException 400 without a repository.
     */
    suspend operator fun invoke(orgId: Uuid, repo: String?): JsonObject
}

/**
 * [GatePolicyUseCase] on the gates catalogue.
 *
 * @param catalog The catalogue.
 * @param signer The instance's key (`GET /api/v1/instance/keys`).
 * @param clock The issue date.
 */
class GatePolicyUseCaseImpl(
    private val catalog: GatesCatalogUseCase,
    private val signer: InstanceSigner,
    private val clock: Clock,
) : GatePolicyUseCase {
    override suspend fun invoke(orgId: Uuid, repo: String?): JsonObject {
        val named = repo.orEmpty().trim().takeIf { it.isNotEmpty() && it.length <= MAX_REPO }
            ?: throw OstromException(ErrorKind.INVALID_REQUEST, "A gate policy is signed for one repository.")
        val issued = clock.now()
        val policy = catalogJson(catalog(orgId, named))
        val payload = JsonObject(
            buildJsonObject {
                put("repo", named)
                put("issuedAt", issued.toString())
                put("expiresAt", (issued + LIFETIME).toString())
            } + policy,
        ).toString()
        return buildJsonObject {
            put("format", FORMAT)
            put("alg", signer.algorithm)
            put("kid", signer.keyId)
            put("signature", Base64.encode(signer.sign(payload.encodeToByteArray())))
            put("payload", payload)
        }
    }

    private companion object {
        const val FORMAT = "ostrom.gates/1"
        const val MAX_REPO = 200

        // The hooks refuse a policy valid for longer than an hour; the relay fetches it again on expiry.
    }
}

/**
 * The gates catalogue as the hooks read it, signed or not: the gates once each, every action, closed on failure.
 *
 * @param entries The catalogue, in order.
 * @return `{gates, actions, failMode, waitSeconds}`.
 */
fun catalogJson(entries: List<CatalogEntry>): JsonObject = buildJsonObject {
    val gated = entries.filter { it.gate != null }.distinctBy { it.gate }
    put("gates", JsonArray(gated.map { RulesHarness.gateJson(it) }))
    put("actions", JsonArray(entries.map { RulesHarness.actionJson(it) }))
    put("failMode", "closed")
    put("waitSeconds", WAIT_SECONDS)
}

private const val WAIT_SECONDS = 300

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val LIFETIME = 10.minutes
