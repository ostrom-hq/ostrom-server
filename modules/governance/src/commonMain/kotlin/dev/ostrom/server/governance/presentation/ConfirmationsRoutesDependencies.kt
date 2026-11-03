// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.presentation

import dev.ostrom.server.governance.domain.AnswerConfirmationUseCase
import dev.ostrom.server.governance.domain.ConfirmationPeople
import dev.ostrom.server.governance.domain.GetConfirmationUseCase
import kotlin.time.Clock

/**
 * What the confirmations routes need.
 *
 * @property get Reads a confirmation.
 * @property answer Answers one.
 * @property people Names the people.
 * @property clock The time.
 */
data class ConfirmationsRoutesDependencies(
    val get: GetConfirmationUseCase,
    val answer: AnswerConfirmationUseCase,
    val people: ConfirmationPeople,
    val clock: Clock,
)
