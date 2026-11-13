// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.models

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A signed decision on something else than a gate (contract `Decision`): a rule written, accepted, edited, rejected,
 * reopened or deleted, or a policy changed. Each one has its receipt.
 *
 * @property orgId The organization.
 * @property id The decision id (`dec_…`).
 * @property at When.
 * @property by Who decided.
 * @property subject What was decided.
 * @property action The rule's new status (`accepted`, `edited`, `rejected`, `proposed`), `deleted`, or `edited` for a
 *   policy.
 * @property before What the subject was, or null for a new one.
 * @property after What it is, or null once deleted.
 * @property ruleDigest The digest of the whole rule the decision names (after it, or before a deletion); null for a
 *   policy. The receipt names the rule by it.
 * @property signed Whether the receipt is signed yet: a decision is committed first, its receipt signed right after,
 *   and signed again at the next read when that failed (as the gates do).
 */
data class Decision(
    val orgId: Uuid,
    val id: String,
    val at: Instant,
    val by: String,
    val subject: DecisionSubject,
    val action: String,
    val before: JsonObject?,
    val after: JsonObject?,
    val ruleDigest: String? = null,
    val signed: Boolean = false,
) {
    /** The receipt's id, known before it is signed: signing is idempotent on it. */
    val receiptId: String get() = "rcpt_$id"
}

/**
 * What a decision is about.
 *
 * @property kind `rule` or `policy`.
 * @property id The rule id, or the policy's name.
 * @property label What people read.
 */
data class DecisionSubject(val kind: String, val id: String, val label: String?)

/**
 * Which decisions to list, newest first.
 *
 * @property subjectKind Only this kind, when set.
 * @property subjectId Only this subject, when set.
 * @property before Only those older than this decision (the cursor of the previous page), when set.
 * @property limit At most this many.
 */
data class DecisionQuery(
    val subjectKind: String? = null,
    val subjectId: String? = null,
    val before: String? = null,
    val limit: Int = PAGE,
) {
    /** The page size. */
    companion object {
        /** The decisions of one page. */
        const val PAGE: Int = 50
    }
}

/**
 * Who decides: a person signed in to the console, owner or admin of the organization.
 *
 * @property orgId The organization.
 * @property name The person's login, written in the decision and its receipt.
 * @property actor Their credential, written with each change in the organization's audit log (`rule.*`).
 */
data class Decider(val orgId: Uuid, val name: String, val actor: AuthenticatedPrincipal)
