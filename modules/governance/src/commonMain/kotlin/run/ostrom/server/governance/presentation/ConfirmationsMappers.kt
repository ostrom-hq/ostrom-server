// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.presentation

import run.ostrom.server.governance.domain.Confirmation
import run.ostrom.server.governance.domain.ConfirmationAnswer
import run.ostrom.server.governance.domain.ConfirmationCredential
import run.ostrom.server.governance.domain.ConfirmationPeople
import run.ostrom.server.governance.domain.ConfirmationPerson
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.json.JsonNull
import kotlin.time.Instant

/**
 * The contract's view of a confirmation at [now]: a pending one past its expiry reads expired.
 *
 * @param people Names the people.
 * @param now The current time.
 *
 * @return The DTO.
 */
internal suspend fun Confirmation.toDto(people: ConfirmationPeople, now: Instant) = ConfirmationDto(
    id = id,
    kind = write.kind,
    status = statusAt(now).wire,
    method = method,
    path = path,
    scope = scope,
    summary = summary,
    before = before ?: JsonNull,
    after = after ?: JsonNull,
    requestedBy = requestedBy.toDto(people),
    credential = credential.wire,
    token = tokenOf(this),
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString(),
    answeredBy = answeredBy?.toDto(people),
    answeredAt = answeredAt?.toString(),
    result = result,
    error = error?.let { ConfirmationErrorDto(it.type, it.message) },
)

/** The personal token of an `api_token` confirmation, always with it and never with another credential. */
private fun tokenOf(confirmation: Confirmation): ConfirmationTokenDto? =
    if (confirmation.credential == ConfirmationCredential.API_TOKEN) {
        ConfirmationTokenDto(checkNotNull(confirmation.tokenId), checkNotNull(confirmation.tokenName))
    } else {
        null
    }

private suspend fun ConfirmationPerson.toDto(people: ConfirmationPeople): ConfirmationPersonDto {
    val name = people.nameOf(userId)
    return ConfirmationPersonDto(memberId.toString(), name?.login, name?.name)
}

/**
 * The answer of a request body.
 *
 * @return Confirm or refuse.
 *
 * @throws OstromException A 400 for anything else.
 */
internal fun AnswerConfirmationRequest.toAnswer(): ConfirmationAnswer = when (answer) {
    "confirm" -> ConfirmationAnswer.CONFIRM
    "refuse" -> ConfirmationAnswer.REFUSE
    else -> throw OstromException(ErrorKind.INVALID_REQUEST, "answer: confirm or refuse.")
}
