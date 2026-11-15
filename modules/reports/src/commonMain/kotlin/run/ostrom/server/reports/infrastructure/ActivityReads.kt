// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.rowLess
import run.ostrom.server.reports.domain.ActivityCursor
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LessEqOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.concat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.regexp
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.unionAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A page of the activity, newest first by (`at`, `item_id`): each source gives its newest `limit` rows in that very
 * order (its index gives the time, the id only breaks ties), then the union keeps the newest `limit`. A gate's line
 * carries the repository of its subject ([gateRepo]); a workflow's carries none. With a repository, only sessions are
 * read.
 *
 * @param orgId The organization.
 * @param before The page starts before this position, when set.
 * @param repo Only this repository's sessions, when set.
 * @param limit The most lines.
 */
internal class ActivityReads(
    private val orgId: Uuid,
    private val before: ActivityCursor?,
    private val repo: String?,
    private val limit: Int,
) {
    private val sessions = ReportsSessions
    private val gates = ReportsGates
    private val decisions = ReportsDecisions

    /** The line's id. */
    val itemId = concat(stringLiteral(SESSION_STARTED_PREFIX), sessions.id).alias("item_id")

    /** When it happened. */
    val at = sessions.startedAt.alias("at")

    /** Its kind's wire name. */
    val kind = stringLiteral(SESSION_STARTED).alias("kind")

    /** Who: an agent, an asker, or the person who decided (a user's id, shown by login). */
    val actor = sessions.agent.alias("actor")

    /** What: a developer, a gate's label, a workflow. */
    val subject = sessions.dev.alias("subject")

    /** A decision's outcome. */
    val outcome = Cast<String?>(Op.nullOp<String>(), TextColumnType()).alias("outcome")

    /** The repository. */
    val repository = sessions.repo.alias("repo")

    /** An ended session's cost, in dollars. */
    val costUsd = Cast<Double?>(Op.nullOp<Double>(), DoubleColumnType()).alias("cost_usd")

    /** The session. */
    val sessionId = sessions.id.alias("session_id")

    /** The gate. */
    val gateId = Cast<String?>(Op.nullOp<String>(), TextColumnType()).alias("gate_id")

    // The plain bound on the time lets the index start at the cursor; the row comparison breaks the ties.
    private fun beforeCursor(at: Expression<*>, id: Expression<String>): Op<Boolean>? {
        val cursor = before ?: return null
        val atParam = QueryParameter(cursor.at, InstantColumnType())
        return LessEqOp(at, atParam) and rowLess(listOf(at, id), listOf(atParam, stringParam(cursor.id)))
    }

    private fun ofRepo(): Op<Boolean>? = repo?.let { sessions.repo eq it }

    private fun started(): Query = sessions
        .select(itemId, at, kind, actor, subject, outcome, repository, costUsd, sessionId, gateId)
        .where {
            (sessions.orgId eq orgId).andIfNotNull(ofRepo())
                .andIfNotNull(beforeCursor(sessions.startedAt, itemId.delegate))
        }
        .orderBy(sessions.startedAt to SortOrder.DESC, itemId.delegate to SortOrder.DESC)
        .limit(limit)

    private fun ended(): Query {
        val id = concat(stringLiteral("$SESSION_ENDED:"), sessions.id)
        return sessions
            .select(
                id, sessions.endedAt, stringLiteral(SESSION_ENDED), sessions.agent, sessions.dev, Op.nullOp<String>(),
                sessions.repo, sessions.costUsd, sessions.id, Op.nullOp<String>(),
            )
            .where {
                ((sessions.orgId eq orgId) and sessions.endedAt.isNotNull()).andIfNotNull(ofRepo())
                    .andIfNotNull(beforeCursor(sessions.endedAt, id))
            }
            .orderBy(sessions.endedAt to SortOrder.DESC, id to SortOrder.DESC)
            .limit(limit)
    }

    private fun asked(): Query {
        val id = concat(stringLiteral("$GATE_REQUESTED:"), gateText())
        // The askers are a JSON array, the first one opened the gate.
        val firstAsker = coalesce(jsonText(gates.askedBy, intLiteral(0)), stringLiteral(""))
        return gates
            .select(
                id, gates.createdAt, stringLiteral(GATE_REQUESTED), firstAsker, gates.label, Op.nullOp<String>(),
                gateRepo(), Op.nullOp<Double>(), Op.nullOp<String>(), gateText(),
            )
            .where {
                ((gates.orgId eq orgId) and (gates.status notInList listOf("SHADOW", "UNGATED")))
                    .andIfNotNull(beforeCursor(gates.createdAt, id))
            }
            .orderBy(gates.createdAt to SortOrder.DESC, id to SortOrder.DESC)
            .limit(limit)
    }

    private fun decided(): Query {
        val id = concat(stringLiteral("$GATE_DECIDED:"), gateText())
        val verdict = case().When(gates.status eq "REJECTED", stringLiteral("rejected")).Else(stringLiteral("approved"))
        return gates
            .select(
                id, gates.decidedAt, stringLiteral(GATE_DECIDED), gates.decidedBy, gates.label, verdict, gateRepo(),
                Op.nullOp<Double>(), Op.nullOp<String>(), gateText(),
            )
            .where {
                ((gates.orgId eq orgId) and gates.decidedAt.isNotNull() and gates.decidedBy.isNotNull())
                    .andIfNotNull(beforeCursor(gates.decidedAt, id))
            }
            .orderBy(gates.decidedAt to SortOrder.DESC, id to SortOrder.DESC)
            .limit(limit)
    }

    private fun changed(): Query {
        val id = concat(stringLiteral("$WORKFLOW_CHANGED:"), decisions.id)
        val decidedAt = Cast<Instant>(decisions.decidedAt, InstantColumnType())
        return decisions
            .select(
                id, decidedAt, stringLiteral(WORKFLOW_CHANGED), decisions.decidedBy, decisions.subjectId,
                Op.nullOp<String>(), Op.nullOp<String>(), Op.nullOp<Double>(), Op.nullOp<String>(),
                Op.nullOp<String>(),
            )
            .where {
                ((decisions.orgId eq orgId) and (decisions.subjectKind eq "workflow"))
                    .andIfNotNull(beforeCursor(decidedAt, id))
            }
            .orderBy(decidedAt to SortOrder.DESC, id to SortOrder.DESC)
            .limit(limit)
    }

    // A gate's id as text.
    private fun gateText(): Expression<String> = Cast(gates.id, TextColumnType())

    // The repository of a gate's subject (`owner/name`): the one the command really ran in when the hook read another
    // (`realRepo`), else the one its session declared (`repo`); none outside a repository. The subject is the gate's
    // own row, so it is never another organization's.
    private fun gateRepo(): Expression<String?> = nullIfEmpty(
        coalesce(
            nullIfEmpty(jsonText(gates.subject, stringLiteral("realRepo"))),
            jsonText(gates.subject, stringLiteral("repo")),
        ),
    )

    /** The sources, each limited, then the newest of them; the person who decided shown by login. */
    private val feed by lazy {
        val sessionsOnly = started().unionAll(ended())
        val sources = if (repo != null) sessionsOnly else sessionsOnly.unionAll(asked()).unionAll(decided())
            .unionAll(changed())
        sources.alias("feed")
    }

    private val members = ReportsMembers
    private val users = ReportsUsers

    /** A page's line's id. */
    val lineId by lazy { feed[itemId] }

    /** A page's line's time. */
    val lineAt by lazy { feed[at] }

    /** A page's line's kind. */
    val lineKind by lazy { feed[kind] }

    /** A page's line's actor: the login of the person who decided if they are a member, else as it is. */
    val lineActor: Expression<String> by lazy { coalesce(users.login, feed[actor]) }

    /** A page's line's subject. */
    val lineSubject by lazy { feed[subject] }

    /** A page's line's outcome. */
    val lineOutcome by lazy { feed[outcome] }

    /** A page's line's repository. */
    val lineRepo by lazy { feed[repository] }

    /** A page's line's cost, in dollars. */
    val lineCost by lazy { feed[costUsd] }

    /** A page's line's session. */
    val lineSession by lazy { feed[sessionId] }

    /** A page's line's gate. */
    val lineGate by lazy { feed[gateId] }

    /**
     * The page, newest first.
     *
     * @return The read.
     */
    fun page(): Query {
        // A person who decided, kept as a user's id, is shown by their login, and only if they are a member of the
        // organization: an id written anywhere else, or of a stranger, is shown as it is (never another org's login).
        val actorId = case()
            .When(feed[actor] regexp UUID_PATTERN, Cast<Uuid>(feed[actor], UuidColumnType()))
            .Else(Op.nullOp())
        return feed
            .join(members, JoinType.LEFT, additionalConstraint = {
                (members.orgId eq orgId) and (lineKind inList DECIDED_BY_A_PERSON) and (members.userId eq actorId)
            })
            .join(users, JoinType.LEFT, additionalConstraint = { users.id eq members.userId })
            .select(
                lineId, lineAt, lineKind, lineActor, lineSubject, lineOutcome, lineRepo, lineCost, lineSession,
                lineGate,
            )
            .orderBy(lineAt to SortOrder.DESC, lineId to SortOrder.DESC)
            .limit(limit)
    }

    private companion object {
        const val SESSION_STARTED = "session.started"
        const val SESSION_STARTED_PREFIX = "$SESSION_STARTED:"
        const val SESSION_ENDED = "session.ended"
        const val GATE_REQUESTED = "gate.requested"
        const val GATE_DECIDED = "gate.decided"
        const val WORKFLOW_CHANGED = "workflow.changed"
        val DECIDED_BY_A_PERSON = listOf(GATE_DECIDED, WORKFLOW_CHANGED)
        const val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    }
}
