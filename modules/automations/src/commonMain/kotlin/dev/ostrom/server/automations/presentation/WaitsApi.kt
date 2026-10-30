package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.Wait
import dev.ostrom.server.automations.domain.WaitSpec
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
 */
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
 * The body of `POST /api/v1/waits`.
 *
 * @property question The question.
 * @property sessionId The session that waits.
 * @property runId The run that waits.
 * @property assignee The member asked; the caller by default.
 * @property kind `human_request`.
 */
@Serializable
data class CreateWaitRequest(
    val question: WaitSpec,
    val sessionId: String? = null,
    val runId: String? = null,
    val assignee: String? = null,
    val kind: String? = null,
)

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
 */
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
)
