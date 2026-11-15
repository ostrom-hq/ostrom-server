// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.runners.domain.CloudInferenceCredentialStore
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.InferenceIssuance
import run.ostrom.server.runners.domain.InferenceToken
import run.ostrom.server.runners.domain.RunnerInferenceTokens
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** The launches module's agent tokens, by hand: [open] says whether the execution opened its launch. */
class FakeInferenceTokens(private val clock: Clock = Clock.System) : RunnerInferenceTokens {
    var open = true
    var failure: Throwable? = null
    val issued = mutableListOf<InferenceToken>()
    val revoked = mutableListOf<Uuid>()

    override suspend fun issue(session: CloudSession, lifetime: Duration): InferenceToken? {
        if (!open) return null
        return InferenceToken(Uuid.random(), "osa_${issued.size + 1}", clock.now() + lifetime).also { issued += it }
    }

    override suspend fun revoke(orgId: Uuid, ids: List<Uuid>) {
        failure?.let { throw it }
        revoked += ids
    }

    /** The secrets issued and not revoked. */
    fun live(): List<String> = issued.filter { it.id !in revoked }.map { it.secret }
}

/** A credential store answering [outcome], recording what it was told. */
class FakeInferenceStore(var outcome: InferenceIssuance = InferenceIssuance.NoLaunch) : CloudInferenceCredentialStore {
    val ended = mutableListOf<CloudSession>()

    override suspend fun issue(session: CloudSession, ttl: Duration, keep: Int): InferenceIssuance = outcome

    override suspend fun revokeEnded(session: CloudSession) {
        ended += session
    }

    override suspend fun purge(limit: Int): Int = 0
}
