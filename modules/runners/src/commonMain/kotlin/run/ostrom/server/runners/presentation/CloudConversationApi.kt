// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/v1/cloud/sessions/{id}/events` (`listCloudEvents`). The cursors stay strings, so a malformed one is the
 * stable `invalid_cursor`; the page size is a number, as the collection's own `limit` reads the same query.
 *
 * @property parent The session.
 */
@Serializable
@Resource("events")
class CloudEventsApi(
    val parent: CloudSessionsApi.Id,
    val after: String? = null,
    val before: String? = null,
    val limit: Int? = null,
    val feed: String? = null,
) {
    /**
     * `GET /api/v1/cloud/sessions/{id}/events/stream` (`streamCloudSession`), server-sent events.
     *
     * @property parent The events.
     */
    @Serializable
    @Resource("stream")
    class Stream(val parent: CloudEventsApi, val after: String? = null)
}

/**
 * `GET /api/v1/cloud/sessions/{id}/agents` (`listCloudSubagents`).
 *
 * @property parent The session.
 */
@Serializable
@Resource("agents")
class CloudAgentsApi(val parent: CloudSessionsApi.Id)

/**
 * `GET` (`listCloudInputs`) and `POST` (`sendCloudMessage`) `/api/v1/cloud/sessions/{id}/messages`.
 *
 * @property parent The session.
 */
@Serializable
@Resource("messages")
class CloudMessagesApi(val parent: CloudSessionsApi.Id, val before: String? = null, val limit: Int? = null) {
    /**
     * `GET /api/v1/cloud/sessions/{id}/messages/{input}` (`getCloudInput`).
     *
     * @property parent The messages.
     */
    @Serializable
    @Resource("{input}")
    class Input(val parent: CloudMessagesApi, val input: String)
}

/** `POST /api/v1/cloud/sessions/{id}/interrupt` (`interruptCloudSession`). */
@Serializable
@Resource("interrupt")
class CloudInterruptApi(val parent: CloudSessionsApi.Id)

/** `POST /api/v1/cloud/sessions/{id}/permissions` (`answerCloudPermission`). */
@Serializable
@Resource("permissions")
class CloudPermissionsApi(val parent: CloudSessionsApi.Id)

/** `PUT /api/v1/cloud/sessions/{id}/permission-bypass` (`setCloudPermissionBypass`). */
@Serializable
@Resource("permission-bypass")
class CloudPermissionBypassApi(val parent: CloudSessionsApi.Id)

/** `PUT /api/v1/cloud/sessions/{id}/model` (`setCloudSessionModel`). */
@Serializable
@Resource("model")
class CloudModelApi(val parent: CloudSessionsApi.Id)

/** `PUT /api/v1/cloud/sessions/{id}/effort` (`setCloudSessionEffort`). */
@Serializable
@Resource("effort")
class CloudEffortApi(val parent: CloudSessionsApi.Id)

/** `POST /api/v1/cloud/sessions/{id}/tasks/stop` (`stopCloudTask`). */
@Serializable
@Resource("tasks/stop")
class CloudStopTaskApi(val parent: CloudSessionsApi.Id)

/** ostrom-core's `CloudEvent`. */
@Serializable
data class CloudEventDto(
    val seq: Long,
    val type: String,
    val at: String,
    val epoch: Long,
    val data: JsonObject,
    val feed: String? = null,
)

/** ostrom-core's `CloudEventPage`. */
@Serializable
data class CloudEventPageDto(
    val events: List<CloudEventDto>,
    val firstSeq: Long? = null,
    val lastSeq: Long? = null,
    val nextAfter: Long? = null,
    val nextBefore: Long? = null,
)

/** ostrom-core's `SendCloudMessageRequest`; files are not served yet, so `fileIds` must stay empty. */
@Serializable
data class SendCloudMessageBody(
    val text: String? = null,
    val wake: Boolean = true,
    val clientMessageId: String? = null,
    val expectedLastEventSeq: Long? = null,
    val fileIds: List<String> = emptyList(),
)

/** ostrom-core's `CloudInput`. */
@Serializable
data class CloudInputDto(
    val id: String,
    val kind: String,
    val status: String,
    val authorId: String,
    val text: String? = null,
    val wake: Boolean = true,
    val woke: Boolean = false,
    val clientMessageId: String? = null,
    val createdAt: String,
    val deliveredAt: String? = null,
    val deliveredEpoch: Long? = null,
    val consumedAt: String? = null,
    val finishedAt: String? = null,
    val reason: String? = null,
    val eventSeq: Long? = null,
    val fileIds: List<String> = emptyList(),
)

/** ostrom-core's `CloudInputPage`. */
@Serializable
data class CloudInputPageDto(val inputs: List<CloudInputDto>, val nextBefore: String? = null)

/** ostrom-core's `AnswerCloudPermissionRequest`. */
@Serializable
data class AnswerCloudPermissionBody(
    val requestId: String,
    val behavior: String,
    val message: String? = null,
    val answers: List<CloudQuestionAnswerDto>? = null,
)

/** ostrom-core's `CloudQuestionAnswer`. */
@Serializable
data class CloudQuestionAnswerDto(val question: Int, val options: List<Int> = emptyList(), val text: String? = null)

/** ostrom-core's `CloudSubagent`. */
@Serializable
data class CloudSubagentDto(
    val id: String,
    val type: String? = null,
    val description: String = "",
    val state: String,
    val startedAt: String,
    val endedAt: String? = null,
    val steps: Int = 0,
    val parentId: String? = null,
    val taskId: String? = null,
)

/** ostrom-core's `CloudSubagentList`. */
@Serializable
data class CloudSubagentListDto(val agents: List<CloudSubagentDto>)

/** ostrom-core's `StopCloudTaskRequest`. */
@Serializable
data class StopCloudTaskBody(val taskId: String)

/** ostrom-core's `SetCloudPermissionBypassRequest`. */
@Serializable
data class SetCloudPermissionBypassBody(val enabled: Boolean)

/** ostrom-core's `SetCloudModelRequest`. */
@Serializable
data class SetCloudModelBody(val model: String)

/** ostrom-core's `SetCloudEffortRequest`. */
@Serializable
data class SetCloudEffortBody(val effort: String)

/** ostrom-core's `CloudSessionWaiting`. */
@Serializable
data class CloudSessionWaitingDto(
    val reason: String,
    val requestId: String? = null,
    val since: String? = null,
    val eventSeq: Long? = null,
)
