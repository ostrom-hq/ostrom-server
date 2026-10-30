// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.integrations.slack.domain.HandleSlackClickUseCase
import dev.ostrom.server.integrations.slack.domain.HandleSlackClickUseCaseImpl
import dev.ostrom.server.integrations.slack.domain.SlackApi
import dev.ostrom.server.integrations.slack.domain.SlackGateChannel
import dev.ostrom.server.integrations.slack.domain.SlackLinksUseCase
import dev.ostrom.server.integrations.slack.domain.SlackLinksUseCaseImpl
import dev.ostrom.server.integrations.slack.domain.SlackMessages
import dev.ostrom.server.integrations.slack.domain.SlackRepository
import dev.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import dev.ostrom.server.integrations.slack.infrastructure.PostgresSlackRepository
import dev.ostrom.server.integrations.slack.infrastructure.SlackMigrations
import dev.ostrom.server.integrations.slack.infrastructure.SlackSettings
import dev.ostrom.server.integrations.slack.infrastructure.SlackSignature
import dev.ostrom.server.integrations.slack.presentation.SlackRoutesDependencies
import dev.ostrom.server.integrations.slack.presentation.slackRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.onClose

/** Koin name of the HTTP client to Slack. */
val SLACK_HTTP = named("slack-http")

/**
 * The integrations-slack module: each request of a gate goes as a direct message to its approver when they are
 * linked to Slack ([GateChannel]), and a click on its buttons answers it for a small push only (SECURITY-AUDIT
 * S2-31). Its settings come from the server's configuration (`OSTROM_SLACK_*`).
 */
object SlackIntegrationModule : ServerModule {
    override val name: String = "integrations-slack"

    override val koinModule: Module = module {
        single(SLACK_HTTP) {
            HttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = TIMEOUT_MS
                    requestTimeoutMillis = TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        single { SlackSettings.from(get()) }
        single<SlackRepository> { PostgresSlackRepository(get()) }
        single<SlackApi> {
            val settings = get<SlackSettings>()
            HttpSlackApi(get(SLACK_HTTP), settings.apiUrl, settings.botToken)
        }
        single { SlackMessages(get<SlackSettings>().webOrigin) }
        single { SlackGateChannel(get(), get(), get()) } bind GateChannel::class
        single<HandleSlackClickUseCase> { HandleSlackClickUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<SlackLinksUseCase> { SlackLinksUseCaseImpl(get(), get()) }
        single { SlackSignature(get<SlackSettings>().signingSecret, get()) }
        single { SlackRoutesDependencies(get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SlackMigrations

    override fun routes(route: Route, koin: Koin) {
        route.slackRoutes(koin.get())
    }

    private const val TIMEOUT_MS = 10_000L
}
