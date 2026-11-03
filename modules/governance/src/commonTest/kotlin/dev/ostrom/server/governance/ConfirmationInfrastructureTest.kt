// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance

import dev.ostrom.server.governance.domain.Confirmation
import dev.ostrom.server.governance.domain.ConfirmationAnswer
import dev.ostrom.server.governance.domain.ConfirmationCredential
import dev.ostrom.server.governance.domain.ConfirmationPerson
import dev.ostrom.server.governance.domain.ConfirmationStatus
import dev.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import dev.ostrom.server.governance.domain.OpenConfirmationUseCase
import dev.ostrom.server.governance.infrastructure.ConfirmationSweeper
import dev.ostrom.server.governance.infrastructure.GovernanceConfirmationsImpl
import dev.ostrom.server.governance.infrastructure.LocalConfirmationWaits
import dev.ostrom.server.governance.presentation.AnswerConfirmationRequest
import dev.ostrom.server.governance.presentation.toAnswer
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.GovernanceRequest
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The sweep, the waits, the 202 the kernel answers, and the answer of a request body. */
class ConfirmationInfrastructureTest {
    private class RecordingLogger : Logger {
        val events = mutableListOf<Pair<LogLevel, String>>()

        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            events += level to message
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private class Sweeps(private val failures: MutableList<Exception>) : ExpireConfirmationsUseCase {
        var passes = 0

        override suspend fun inOrganization(orgId: Uuid) = Unit

        override suspend fun everywhere(): Int {
            passes++
            failures.removeFirstOrNull()?.let { throw it }
            return 0
        }
    }

    @Test
    fun theSweepRunsItsPassesLogsAFailureAndStopsWhenCancelled() = runTest {
        val logger = RecordingLogger()
        val sweeps = Sweeps(mutableListOf(IllegalStateException("database")))
        ConfirmationSweeper(sweeps, logger, 10.milliseconds, passes = 2).run()
        assertEquals(2, sweeps.passes)
        assertEquals(listOf(LogLevel.ERROR to "governance confirmation sweep failed"), logger.events)
        val cancelled = Sweeps(mutableListOf(CancellationException("stopping")))
        assertFailsWith<CancellationException> { ConfirmationSweeper(cancelled, logger).run() }
    }

    @Test
    fun aWaitEndsWhenItsConfirmationChangesOrAtItsTime() = runTest {
        val waits = LocalConfirmationWaits()
        val start = testScheduler.currentTime
        waits.await("gcf_1", 2.seconds)
        assertEquals(2_000L, testScheduler.currentTime - start, "Nothing changed: the whole wait")
        val waiting = launch { waits.await("gcf_1", 30.seconds) }
        testScheduler.advanceTimeBy(100)
        waits.changed("gcf_2")
        waits.changed("gcf_1")
        waiting.join()
        assertEquals(2_100L, testScheduler.currentTime - start, "Woken by its own change only")
    }

    @Test
    fun aTokensWriteIsAnsweredWithTheConsolePage() = runTest {
        val expires = Instant.parse("2026-11-02T20:15:00Z")
        val open = object : OpenConfirmationUseCase {
            override suspend fun invoke(request: GovernanceRequest) = Confirmation(
                "gcf_1", request.orgId, request.write, request.scope, request.method, request.path, "Set it", null,
                null, ConfirmationPerson("ada", Uuid.random()), ConfirmationCredential.ADMIN, null,
                ConfirmationStatus.PENDING, expires - 15.seconds, expires,
            )
        }
        val request = GovernanceRequest(
            AuthenticatedPrincipal("ada", PrincipalKind.USER, emptySet()),
            Uuid.random(),
            "gates:policy",
            "PUT",
            "/api/v1/gates/policy",
            GovernanceWrite("gates.policy", JsonObject(emptyMap())),
        )
        val pending = GovernanceConfirmationsImpl(open, "https://app.ostrom.run").open(request)
        assertEquals("gcf_1", pending.confirmationId)
        assertEquals("https://app.ostrom.run/confirm/gcf_1", pending.confirmUrl)
        assertEquals("2026-11-02T20:15:00Z", pending.expiresAt)
    }

    @Test
    fun anAnswerIsConfirmOrRefuse() {
        assertEquals(ConfirmationAnswer.CONFIRM, AnswerConfirmationRequest("confirm").toAnswer())
        assertEquals(ConfirmationAnswer.REFUSE, AnswerConfirmationRequest("refuse").toAnswer())
        assertFailsWith<OstromException> { AnswerConfirmationRequest("yes").toAnswer() }
    }
}
