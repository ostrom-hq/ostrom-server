// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The version of the canonical form of an audit event: a change of one byte is a new version. */
const val AUDIT_FORMAT_VERSION = 1

/**
 * The canonical form of an audit event, the bytes that are hashed and signed: a JSON object with its keys in
 * byte order, no spaces, `null` for absent values, the details as an object with sorted keys. A verifier rebuilds it
 * from the published event and checks the hash, the signature (with the instance key [AuditEvent.keyId]) and that
 * [previousHash] is the hash of the previous event of the chain.
 *
 * @return The canonical JSON.
 */
fun auditCanonicalForm(
    id: Uuid,
    sequence: Long,
    entry: AuditEntry,
    occurredAt: Instant,
    previousHash: String?,
): String {
    val details = JsonObject(entry.details.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) })
    val canonical = buildJsonObject {
        put("action", entry.action)
        put("actorId", entry.actorId?.toString())
        put("details", details)
        put("id", id.toString())
        put("occurredAt", occurredAt.toString())
        put("orgId", entry.orgId?.toString())
        put("previousHash", previousHash)
        put("sequence", sequence)
        put("targetId", entry.targetId)
        put("targetType", entry.targetType)
        put("v", AUDIT_FORMAT_VERSION)
    }
    return CANONICAL_JSON.encodeToString(JsonObject.serializer(), canonical)
}

/** Plain JSON, no pretty printing: the canonical form must not depend on the server's JSON settings. */
private val CANONICAL_JSON = Json

/**
 * [AuditLog] on the repository: each event is chained to the previous one of its chain and signed by the instance
 * key, inside the transaction that locks the chain.
 *
 * @param events The audit repository.
 * @param signer The instance key.
 * @param hasher Hashes the canonical form (SHA-256).
 * @param clock The clock.
 */
class AuditLogImpl(
    private val events: AuditRepository,
    private val signer: InstanceSigner,
    private val hasher: SecretHasher,
    private val clock: Clock,
) : AuditLog {
    override suspend fun record(entry: AuditEntry): AuditEvent = events.append(entry.orgId, sign(entry))

    override fun sign(entry: AuditEntry): suspend (String?, Long) -> AuditEvent = { previousHash, sequence ->
        val id = Uuid.random()
        val occurredAt = clock.nowMillis()
        val canonical = auditCanonicalForm(id, sequence, entry, occurredAt, previousHash)
        AuditEvent(
            id = id,
            sequence = sequence,
            entry = entry,
            occurredAt = occurredAt,
            previousHash = previousHash,
            hash = hasher.hash(canonical),
            signature = Base64.encode(signer.sign(canonical.encodeToByteArray())),
            keyId = signer.keyId,
        )
    }
}

/** Lists audit events, newest first, by pages. */
interface ListAuditEventsUseCase {
    /**
     * Lists the events.
     *
     * @param orgId The organization, or null for the caller's account-level events. The orgs module checks the
     *   caller may read an organization's log before calling.
     * @param actorId For the account-level chain, the user whose events to list.
     * @param beforeSequence The `sequence` of the last event of the previous page, or null.
     * @param limit The page size, clamped to 1..200.
     *
     * @return The events.
     */
    suspend operator fun invoke(orgId: Uuid?, actorId: Uuid?, beforeSequence: Long?, limit: Int): List<AuditEvent>
}

/**
 * [ListAuditEventsUseCase] on the repository.
 *
 * @param events The audit repository.
 */
class ListAuditEventsUseCaseImpl(private val events: AuditRepository) : ListAuditEventsUseCase {
    override suspend fun invoke(orgId: Uuid?, actorId: Uuid?, beforeSequence: Long?, limit: Int): List<AuditEvent> =
        events.list(orgId, actorId, beforeSequence, Pages.size(limit, MAX_PAGE, MAX_PAGE))

    private companion object {
        const val MAX_PAGE = 200
    }
}
