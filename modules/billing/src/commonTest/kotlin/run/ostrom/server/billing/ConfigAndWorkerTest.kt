// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.PaymentIssue
import run.ostrom.server.billing.domain.PaymentIssueKind
import run.ostrom.server.billing.domain.ProcessOutboxUseCase
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.infrastructure.LoggingBillingMetrics
import run.ostrom.server.billing.infrastructure.LoggingBillingNotifier
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Records log lines. */
private class RecordingLogger(val lines: MutableList<String> = mutableListOf()) : Logger {
    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += "$level $message $fields"
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

class ConfigAndWorkerTest {
    @Test
    fun withoutAStripeKeyTheInstanceIsSelfHosted() {
        val config = BillingConfig.load(MapConfigSource(emptyMap()))
        assertNull(config.stripeSecretKey)
        assertEquals(emptyList(), config.webhookSecrets)
        assertEquals("https://api.stripe.com", config.stripeApiBase)
        assertEquals("https://app.ostrom.run", config.consoleUrl)
    }

    @Test
    fun keysAndSecretsComeFromTheEnvironmentOrFromFiles() {
        val files = mapOf("/run/key" to "sk_test_file\n", "/run/hook" to " whsec_a , whsec_b ,")
        val config = BillingConfig.load(
            MapConfigSource(
                mapOf(
                    "OSTROM_STRIPE_SECRET_KEY_FILE" to "/run/key",
                    "OSTROM_STRIPE_WEBHOOK_SECRET_FILE" to "/run/hook",
                    "OSTROM_STRIPE_API_BASE" to "http://localhost:12111",
                    "OSTROM_CONSOLE_URL" to "http://localhost:5173",
                ),
            ),
        ) { files.getValue(it) }
        assertEquals("sk_test_file", config.stripeSecretKey)
        assertEquals(listOf("whsec_a", "whsec_b"), config.webhookSecrets)
        assertEquals("http://localhost:12111", config.stripeApiBase)
        assertEquals("http://localhost:5173", config.consoleUrl)
        val direct = BillingConfig.load(
            MapConfigSource(
                mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_live_x", "OSTROM_STRIPE_WEBHOOK_SECRET" to "whsec_x")
            )
        )
        assertEquals("sk_live_x", direct.stripeSecretKey)
    }

    @Test
    fun aKeyWithoutWebhookSecretFailsAtStartup() {
        assertFailsWith<InvalidConfigException> {
            BillingConfig.load(MapConfigSource(mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_test_x")))
        }
        assertFailsWith<InvalidConfigException> {
            val source = mapOf("OSTROM_STRIPE_SECRET_KEY" to "sk_test_x", "OSTROM_STRIPE_WEBHOOK_SECRET_FILE" to "/e")
            BillingConfig.load(MapConfigSource(source)) { "  " }
        }
    }

    @Test
    fun aSecretFileIsReadFromTheFilesystem() {
        val file = Path(SystemTemporaryDirectory, "ostrom-billing-${Uuid.random()}.key")
        SystemFileSystem.sink(file).buffered().use { it.writeString("sk_test_disk\n") }
        try {
            val source = MapConfigSource(
                mapOf("OSTROM_STRIPE_SECRET_KEY_FILE" to file.toString(), "OSTROM_STRIPE_WEBHOOK_SECRET" to "w"),
            )
            assertEquals("sk_test_disk", BillingConfig.load(source).stripeSecretKey)
        } finally {
            SystemFileSystem.delete(file)
        }
    }

    @Test
    fun aMissingSecretFileFailsAtStartup() {
        assertFailsWith<InvalidConfigException> {
            BillingConfig.load(
                MapConfigSource(mapOf("OSTROM_STRIPE_SECRET_KEY_FILE" to "/nonexistent/ostrom-stripe.key"))
            )
        }
    }

    @Test
    fun theWorkerSurvivesAFailedPassAndStopsAfterItsPasses() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val process = object : ProcessOutboxUseCase {
            override suspend fun invoke(limit: Int): Int {
                calls++
                if (calls == 1) error("database down")
                return 0
            }
        }
        OutboxWorker(process, logger, interval = 1.seconds, batch = 5, passes = 3).run()
        assertEquals(3, calls)
        assertEquals(listOf("ERROR billing outbox pass failed {error=IllegalStateException}"), logger.lines)
    }

    @Test
    fun theWorkerPropagatesCancellation() = runTest {
        val process = object : ProcessOutboxUseCase {
            override suspend fun invoke(limit: Int): Int = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { OutboxWorker(process, RecordingLogger()).run() }
    }

    @Test
    fun theLoggingNotifierLogsWithoutSecrets() = runTest {
        val logger = RecordingLogger()
        val org = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")
        val notifier = LoggingBillingNotifier(logger)
        notifier.agentHoursReached(org, 80, 240, 300)
        notifier.paymentIssue(org, PaymentIssue(PaymentIssueKind.FAILED, "https://pay", NOW))
        assertEquals(
            listOf(
                "INFO agent hours alert {org_id=$org, percent=80, used_hours=240, limit_hours=300}",
                "WARN payment issue {org_id=$org, kind=payment_failed}",
            ),
            logger.lines,
        )
    }

    @Test
    fun anEventForAnUnknownOrganizationIsLoggedAndCounted() {
        val logger = RecordingLogger()
        val metrics = LoggingBillingMetrics(logger)
        assertEquals(0, metrics.ignoredUnknownOrg)
        metrics.stripeEventForUnknownOrganization("evt_1", "customer.subscription.updated")
        metrics.stripeEventForUnknownOrganization("evt_2", "invoice.paid")
        assertEquals(2, metrics.ignoredUnknownOrg)
        assertEquals(
            listOf(
                "WARN stripe event ignored {outcome=ignored_unknown_org, event_id=evt_1, " +
                    "type=customer.subscription.updated, count=1}",
                "WARN stripe event ignored {outcome=ignored_unknown_org, event_id=evt_2, type=invoice.paid, count=2}",
            ),
            logger.lines,
        )
    }
}
