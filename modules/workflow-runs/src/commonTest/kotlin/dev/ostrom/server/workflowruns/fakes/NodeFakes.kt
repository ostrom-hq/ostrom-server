// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.fakes

import dev.ostrom.server.workflowruns.domain.services.ChildOpening
import dev.ostrom.server.workflowruns.domain.services.ChildRequest
import dev.ostrom.server.workflowruns.domain.services.ChildResult
import dev.ostrom.server.workflowruns.domain.services.RunChildren
import dev.ostrom.server.workflowruns.domain.services.RunWaits
import dev.ostrom.server.workflowruns.domain.services.WaitOpening
import dev.ostrom.server.workflowruns.domain.services.WaitRequest
import dev.ostrom.server.workflowruns.domain.services.WaitResult
import dev.ostrom.server.workflowruns.domain.services.WaitState
import kotlin.uuid.Uuid

/** The wait every [FakeWaits] asks. */
val WAIT: Uuid = Uuid.parse("0000000e-0000-0000-0000-000000000001")

/** The child run every [FakeChildren] starts. */
const val CHILD: String = "wfr_cccccccccccccccccccccccccccccccc"

/** Waits asked and answered by the test. */
class FakeWaits(var result: WaitResult? = WaitResult(WaitState.OPEN), var refusal: String? = null) : RunWaits {
    val asked = mutableListOf<WaitRequest>()
    val cancelled = mutableListOf<Uuid>()

    override suspend fun ask(orgId: Uuid, request: WaitRequest): WaitOpening {
        asked += request
        return refusal?.let { WaitOpening(refusal = it) } ?: WaitOpening(WAIT)
    }

    override suspend fun result(orgId: Uuid, waitId: Uuid): WaitResult? = result

    override suspend fun cancel(orgId: Uuid, starter: String, waitId: Uuid) {
        cancelled += waitId
    }
}

/** Child runs started and ended by the test. */
class FakeChildren(var result: ChildResult? = null, var refusal: String? = null) : RunChildren {
    val started = mutableListOf<ChildRequest>()
    val cancelled = mutableListOf<String>()

    override suspend fun start(orgId: Uuid, request: ChildRequest): ChildOpening {
        started += request
        return refusal?.let { ChildOpening(refusal = it) } ?: ChildOpening(CHILD)
    }

    override suspend fun result(orgId: Uuid, runId: String): ChildResult? = result

    override suspend fun cancel(orgId: Uuid, runId: String) {
        cancelled += runId
    }
}
