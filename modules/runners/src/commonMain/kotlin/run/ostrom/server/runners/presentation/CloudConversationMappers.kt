// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.runners.domain.CloudEvent
import run.ostrom.server.runners.domain.CloudEventPage
import run.ostrom.server.runners.domain.CloudInput
import run.ostrom.server.runners.domain.CloudSubagent
import run.ostrom.server.runners.domain.storedObject
import kotlinx.serialization.json.JsonObject
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.PermissionAnswer
import run.ostrom.server.runners.domain.QuestionAnswer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Maps an event to the contract; stored data that is not an object reads as an empty one. */
internal fun CloudEvent.toDto() = CloudEventDto(
    seq = seq,
    type = type.wire,
    at = at.toString(),
    epoch = epoch,
    data = storedObject(data) ?: JsonObject(emptyMap()),
    feed = feed,
)

internal fun CloudEventPage.toDto() =
    CloudEventPageDto(events.map { it.toDto() }, firstSeq, lastSeq, nextAfter, nextBefore)

internal fun CloudSubagent.toDto() = CloudSubagentDto(
    id, type, description, state, startedAt.toString(), endedAt?.toString(), steps, parentId, taskId,
)

internal fun CloudInput.toDto() = CloudInputDto(
    id = id.toString(),
    kind = kind.wire,
    status = status.wire,
    authorId = authorId,
    text = text,
    wake = wake,
    woke = woke,
    clientMessageId = clientKey,
    createdAt = createdAt.toString(),
    deliveredAt = deliveredAt?.toString(),
    deliveredEpoch = deliveredEpoch,
    consumedAt = consumedAt?.toString(),
    finishedAt = finishedAt?.toString(),
    reason = reason,
    eventSeq = eventSeq,
    fileIds = files.map { it.id.toString() },
)

internal fun AnswerCloudPermissionBody.toDomain(): PermissionAnswer {
    val allow = when (behavior) {
        "allow" -> true
        "deny" -> false
        else -> throw CloudConversationRules.invalid("invalid_permission_answer", "Use behavior allow or deny.")
    }
    return PermissionAnswer(
        requestId,
        allow,
        message,
        answers.orEmpty().map { QuestionAnswer(it.question, it.options, it.text) },
    )
}

/** The answer as the runner reads it; a bare denial in the exact shape an owner-only agent still accepts. */
internal fun answerText(answer: PermissionAnswer): String {
    if (answer.isBareDenial) return CloudConversationRules.bareDenialText(answer.requestId)
    return buildJsonObject {
        put("requestId", answer.requestId)
        put("behavior", if (answer.allow) "allow" else "deny")
        answer.message?.let { put("message", it) }
        if (answer.answers.isNotEmpty()) {
            putJsonArray("answers") {
                answer.answers.forEach { item ->
                    add(
                        buildJsonObject {
                            put("question", item.question)
                            putJsonArray("options") { item.options.forEach { add(it) } }
                            item.text?.let { put("text", it) }
                        },
                    )
                }
            }
        }
    }.toString()
}
