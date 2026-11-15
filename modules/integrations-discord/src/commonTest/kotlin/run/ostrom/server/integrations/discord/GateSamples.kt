// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A push to main that Bob rejected. */
fun sampleDecidedGate(orgId: Uuid, id: Uuid, now: Instant) = Gate(
    id = id,
    orgId = orgId,
    key = "key",
    subject = GateSubject(repo = "acme/app", actionClass = "push-main", command = "git push", scope = "main"),
    node = null,
    label = "Push to main",
    mode = GateMode.ENFORCED,
    status = GateStatus.REJECTED,
    askedBy = listOf("ana"),
    askedVia = emptySet(),
    sessionIds = emptyList(),
    shownDigest = "digest",
    evidence = emptyList(),
    decidedBy = "bob",
    decidedAt = now,
    createdAt = now,
)
