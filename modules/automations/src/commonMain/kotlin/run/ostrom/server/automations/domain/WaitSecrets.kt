// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Keeps the values of secret fields apart from everything a wait shows (TeamCode's `docs/waits.md#secrets`, ported).
 * An answer is split in two: the values stored in the wait, where a secret field reads [PROVIDED] or [NOT_PROVIDED]
 * and never its value, and the [WaitSecret]s the store seals for the session. Nothing else (the answer view, the wake
 * message, the feed, the audit) ever sees the value.
 */
internal object WaitSecrets {
    /** What the stored answer says of a secret field that was filled. */
    const val PROVIDED = "provided"

    /** What the stored answer says of a secret field that was left empty. */
    const val NOT_PROVIDED = "not provided"

    /**
     * An answer split in two.
     *
     * @property values What the wait stores.
     * @property secrets What the store seals apart.
     */
    class Split(val values: Map<String, JsonElement>, val secrets: List<WaitSecret>)

    /**
     * Splits an answer already checked by [WaitRules.answer].
     *
     * @param fields The wait's fields.
     * @param values The answer's values.
     * @param at The answer's instant, from which the secrets' lifetime runs.
     *
     * @return The split.
     */
    fun split(fields: List<WaitField>, values: Map<String, JsonElement>, at: Instant): Split {
        val secretFields = fields.filter { it.type == WaitFieldType.SECRET }
        val kept = LinkedHashMap(values)
        val secrets = mutableListOf<WaitSecret>()
        for (field in secretFields) {
            val value = values[field.id]
            if (WaitRules.filled(value)) {
                val text = (value as JsonPrimitive).content
                secrets += WaitSecret(
                    WaitRules.secretVariable(field.id),
                    field.id,
                    text,
                    field.oneTime,
                    at + WaitRules.SECRET_LIFETIME,
                )
                kept[field.id] = JsonPrimitive(PROVIDED)
            } else {
                kept[field.id] = JsonPrimitive(NOT_PROVIDED)
            }
        }
        return Split(kept, secrets)
    }
}

/**
 * Whether a session takes a secret: only a cloud session, whose runner serves it to the agent, and only Claude Code,
 * the one agent that reads its environment again before each command (`waits.secrets.v1`). The runners module binds
 * it on its cloud sessions.
 */
fun interface WaitSecretSessions {
    /**
     * The agent of a cloud session.
     *
     * @param orgId The organization.
     * @param sessionId The session a wait is asked in.
     *
     * @return The agent's name (`claude`, `codex`…), or null when [sessionId] is no cloud session.
     */
    suspend fun agentOf(orgId: Uuid, sessionId: String): String?
}

/** No cloud session: a server built without the runners module takes no secret. */
internal object NoSecretSessions : WaitSecretSessions {
    override suspend fun agentOf(orgId: Uuid, sessionId: String): String? = null
}

/**
 * [WaitSecretSource] on the repository: the unexpired secrets of the session, a one-time one deleted by being served,
 * which is written to the organization's audit log by variable name (`wait.secret_consumed`), never a value.
 *
 * @param repository Where the secrets are kept.
 * @param clock The time.
 * @param audit The organization's audit log.
 */
class WaitSecretSourceImpl(
    private val repository: WaitsRepository,
    private val clock: Clock,
    private val audit: OrgAuditTrail,
) : WaitSecretSource {
    override suspend fun serve(orgId: Uuid, sessionId: String, ownerId: String): ServedSecrets {
        val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        val served = repository.serveSecrets(orgId, sessionId, ownerId, now)
        if (served.oneTime.isNotEmpty()) {
            audit.record(
                OrgAuditRecord(
                    orgId = orgId,
                    actor = WAITS_ACTOR,
                    event = "wait.secret_consumed",
                    subjectType = "session",
                    subjectId = sessionId,
                    details = auditDetails("variables" to served.oneTime.sorted()),
                ),
            )
        }
        return served
    }
}
