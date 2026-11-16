// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

/**
 * The per-member caps of cloud sessions (TeamCode's `CloudSettings.runningLimit` and `retainedLimit`), as numbers to
 * compare with: [Int.MAX_VALUE] when the setting is 0 (no limit). Local and remote sessions keep no workspace on a
 * cluster and are outside both.
 */
interface MemberSessionCaps {
    /** Sessions one member may run at once (`maxRunningSessionsPerMember`). */
    suspend fun runningLimit(): Int

    /** Sessions one member keeps, paused ones included, not over (`maxRetainedSessionsPerMember`). */
    suspend fun retainedLimit(): Int
}

/** No per-member cap, on a server without the cloud module. */
object NoMemberSessionCaps : MemberSessionCaps {
    override suspend fun runningLimit(): Int = Int.MAX_VALUE

    override suspend fun retainedLimit(): Int = Int.MAX_VALUE
}
