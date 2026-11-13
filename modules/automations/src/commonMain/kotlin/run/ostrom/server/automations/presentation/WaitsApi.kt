// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskedWait
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitSchedule
import run.ostrom.server.automations.domain.WaitSpec
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/v1/waits` and `POST` (ask), as in ostrom-core's `api/openapi.yaml` (operations `listWaits`,
 * `createWait`).
 *
 * @property status `open` (default), `resolved`, `cancelled` or `all`.
 * @property mine Only the waits asked of the caller.
 * @property assignee `all` or a member id, for an administrator.
 * @property sessionId Only this session's.
 * @property runId Only this run's.
 * @property before The cursor of the previous page.
 * @property limit The page size, 1 to 100.
 * @property resolver `human` (default), `time`, `event` or `all`.
 */
@Suppress("LongParameterList") // One property per query parameter of the contract.
@Serializable
@Resource("/api/v1/waits")
class WaitsApi(
    val status: String? = null,
    val mine: Boolean = false,
    val assignee: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val before: String? = null,
    val limit: Int? = null,
    val resolver: String? = null,
) {
    /**
     * `GET /api/v1/waits/{id}`.
     *
     * @property parent The collection.
     * @property id The wait id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: WaitsApi = WaitsApi(), val id: String) {
        /**
         * `POST /api/v1/waits/{id}/answer`.
         *
         * @property parent The wait.
         */
        @Serializable
        @Resource("answer")
        class Answer(val parent: Id)

        /**
         * `POST /api/v1/waits/{id}/cancel`.
         *
         * @property parent The wait.
         */
        @Serializable
        @Resource("cancel")
        class Cancel(val parent: Id)

        /**
         * `POST /api/v1/waits/{id}/event`: the event a wait for an event waits for. No sign-in.
         *
         * @property parent The wait.
         * @property token The wait's secret, unless given in the `Ostrom-Wait-Token` header.
         */
        @Serializable
        @Resource("event")
        class Event(val parent: Id, val token: String? = null)
    }
}

/**
 * `POST /api/v1/waits/mcp`: the waits MCP server of an agent, bound to its session.
 *
 * @property session The session the agent runs in.
 * @property run The workflow run, if any.
 */
@Serializable
@Resource("/api/v1/waits/mcp")
class WaitsMcpApi(val session: String? = null, val run: String? = null)

/**
 * `POST /api/v1/waits/wake`: what the waits of an agent's session have to say to the Stop hook of its launch.
 *
 * @property session The session the agent runs in: any of its segments.
 */
@Serializable
@Resource("/api/v1/waits/wake")
class WaitsWakeApi(val session: String? = null)

/**
 * The body of `POST /api/v1/waits/wake`, optional.
 *
 * @property waitMillis How long the call may be held, 0 to 30 000; 25 000 when absent.
 */
@Serializable
data class WaitWakeRequest(val waitMillis: Long? = null)

/**
 * The answer of `POST /api/v1/waits/wake`, as the contract's `WaitWake`.
 *
 * @property open A wait of the session is still open.
 * @property messages The answers that arrived, oldest first, each handed over once.
 */
@Serializable
data class WaitWakeResponse(val open: Boolean, val messages: List<String>)

/**
 * The body of `POST /api/v1/waits`.
 *
 * @property question The question.
 * @property sessionId The session that waits.
 * @property runId The run that waits.
 * @property assignee The member asked; the caller by default.
 * @property kind `human_request`, or `timed`.
 * @property resolver `human` (by default), `time` or `event`.
 * @property at For a time, the instant it resolves.
 * @property after For a time, seconds from now.
 * @property source For an event, `webhook`.
 * @property timeoutSeconds For an event, how long it waits.
 * @property resumeMessage A line added to the message that wakes the session.
 */
@Suppress("LongParameterList") // One property per field of the contract.
@Serializable
data class CreateWaitRequest(
    val question: WaitSpec,
    val sessionId: String? = null,
    val runId: String? = null,
    val assignee: String? = null,
    val kind: String? = null,
    val resolver: String? = null,
    val at: String? = null,
    val after: Long? = null,
    val source: String? = null,
    val timeoutSeconds: Long? = null,
    val resumeMessage: String? = null,
) {
    /** The ask it names. */
    fun toAsk(): AskWait = AskWait(
        question,
        sessionId,
        runId,
        assignee,
        kind,
        resolver,
        WaitSchedule(at, after, source, timeoutSeconds, resumeMessage),
    )
}

/**
 * The answer of `POST /api/v1/waits/{id}/event`, as the contract's `WaitEventReceived`.
 *
 * @property id The wait.
 * @property status Its status, `resolved`.
 */
@Serializable
data class WaitEventReceivedResponse(val id: String, val status: String)

/**
 * The body of `POST /api/v1/waits/{id}/answer`.
 *
 * @property action The action chosen.
 * @property values The values by field id.
 */
@Serializable
data class AnswerWaitRequest(val action: String, val values: Map<String, JsonElement> = emptyMap())

/**
 * The answer of a wait.
 *
 * @property action The action chosen.
 * @property values The values by field id.
 * @property author The member who answered.
 * @property answeredAt When (ISO 8601).
 */
@Serializable
data class WaitAnswerResponse(val action: String, val values: JsonObject, val author: String, val answeredAt: String)

/**
 * A wait, as the contract's `Wait`.
 *
 * @property id Its id.
 * @property kind `human_request`.
 * @property status `open`, `resolved` or `cancelled`.
 * @property sessionId The session that waits.
 * @property runId The run that waits.
 * @property assignee The member asked.
 * @property createdBy The member who asked.
 * @property question The question.
 * @property answer The answer, once resolved.
 * @property createdAt When it was asked.
 * @property resolvedAt When it was answered or cancelled.
 * @property resolver `human`, `time` or `event`.
 * @property dueAt For a time, when it resolves.
 * @property timeoutAt For an event, when it times out.
 * @property eventSource For an event, `webhook`.
 * @property resumeMessage The line added to the message that wakes the session.
 * @property eventUrl For an event, in the answer that creates it only: the address a CI calls, with its secret.
 */
@Suppress("LongParameterList") // One property per field of the contract.
@Serializable
data class WaitResponse(
    val id: String,
    val kind: String,
    val status: String,
    val sessionId: String? = null,
    val runId: String? = null,
    val assignee: String,
    val createdBy: String,
    val question: WaitSpec,
    val answer: WaitAnswerResponse? = null,
    val createdAt: String,
    val resolvedAt: String? = null,
    val resolver: String? = null,
    val dueAt: String? = null,
    val timeoutAt: String? = null,
    val eventSource: String? = null,
    val resumeMessage: String? = null,
    val eventUrl: String? = null,
)

/**
 * A page of waits.
 *
 * @property items The waits, newest first.
 * @property next The cursor of the next page.
 */
@Serializable
data class WaitsResponse(val items: List<WaitResponse>, val next: String? = null)

/** The contract's form of a wait. */
fun Wait.toResponse(): WaitResponse = WaitResponse(
    id = id.toString(),
    kind = kind,
    status = status.wire,
    sessionId = sessionId,
    runId = runId,
    assignee = assignee,
    createdBy = createdBy,
    question = question,
    answer = answer?.let { WaitAnswerResponse(it.action, JsonObject(it.values), it.author, it.answeredAt.toString()) },
    createdAt = createdAt.toString(),
    resolvedAt = resolvedAt?.toString(),
    resolver = resolver.wire,
    dueAt = timer?.dueAt?.toString(),
    timeoutAt = timer?.timeoutAt?.toString(),
    eventSource = timer?.source,
    resumeMessage = timer?.resumeMessage,
)

/**
 * The contract's form of a wait just asked: a wait for an event carries its address, with its secret, this once.
 *
 * @param publicUrl The server's public URL, without a trailing slash.
 */
fun AskedWait.toResponse(publicUrl: String): WaitResponse =
    wait.toResponse().copy(eventUrl = secret?.let { eventUrlOf(publicUrl, wait.id.toString(), it) })

/** The event address of a wait: its secret in the query, so that a CI calls it with a plain `curl -X POST`. */
private fun eventUrlOf(publicUrl: String, id: String, secret: String): String =
    "$publicUrl/api/v1/waits/$id/event?token=$secret"
