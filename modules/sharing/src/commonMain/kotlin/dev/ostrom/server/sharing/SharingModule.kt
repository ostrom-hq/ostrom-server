// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import dev.ostrom.server.sharing.domain.AccountSpendReader
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.AccountTokens
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import dev.ostrom.server.sharing.domain.NoAccountSpendReader
import dev.ostrom.server.sharing.domain.OAuthRefresher
import dev.ostrom.server.sharing.domain.QuotaHeaderParser
import dev.ostrom.server.sharing.domain.QuotaWindowRepository
import dev.ostrom.server.sharing.domain.RemoveSharedAccountUseCase
import dev.ostrom.server.sharing.domain.RemoveSharedAccountUseCaseImpl
import dev.ostrom.server.sharing.domain.ShareAccountUseCase
import dev.ostrom.server.sharing.domain.ShareAccountUseCaseImpl
import dev.ostrom.server.sharing.domain.SharedAccountPool
import dev.ostrom.server.sharing.domain.SharedAccountRepository
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCaseImpl
import dev.ostrom.server.sharing.infrastructure.HttpOAuthRefresher
import dev.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import dev.ostrom.server.sharing.infrastructure.QuotaWindowRepositoryImpl
import dev.ostrom.server.sharing.infrastructure.SharedAccountRepositoryImpl
import dev.ostrom.server.sharing.infrastructure.SharingMigrations
import dev.ostrom.server.sharing.presentation.SharingRoutesDependencies
import dev.ostrom.server.sharing.presentation.sharingRoutes
import io.ktor.client.HttpClient
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * The sharing module: members pool their personal subscriptions (Claude Pro/Max, ChatGPT for Codex), on by choice of
 * the operator, with per-account limits and usage. The gateway leases from [SharedAccountPool].
 */
object SharingModule : ServerModule {
    override val name: String = "sharing"

    /** The Koin name of the OAuth refresh HTTP client, replaced by tests. */
    val HTTP_CLIENT = named("sharingHttpClient")

    override val koinModule: Module = module {
        single(HTTP_CLIENT) {
            HttpClient {
                followRedirects = false
                expectSuccess = false
            }
        }
        single<SharedAccountRepository> { SharedAccountRepositoryImpl(get()) }
        single<QuotaWindowRepository> { QuotaWindowRepositoryImpl(get()) }
        single { AccountTokenCipher(get()) }
        single<OAuthRefresher> {
            HttpOAuthRefresher(get(HTTP_CLIENT), get(), SharingConfig.tokenEndpoints(get<ConfigSource>()))
        }
        single<QuotaHeaderParser> { ProviderQuotaHeaderParser() }
        single { AccountTokens(get(), get(), get(), get()) }
        single { SharedAccountPool(get(), get(), get(), get(), spend(), get()) }
        single<ShareAccountUseCase> { ShareAccountUseCaseImpl(get(), get(), get()) }
        single<ListSharedAccountsUseCase> { ListSharedAccountsUseCaseImpl(get(), get(), get(), spend(), get()) }
        single<UpdateSharedAccountUseCase> { UpdateSharedAccountUseCaseImpl(get()) }
        single<RemoveSharedAccountUseCase> { RemoveSharedAccountUseCaseImpl(get()) }
        single {
            SharingRoutesDependencies(
                getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }

    /** The usage ledger's spend per account when the gateway binds it; none otherwise. */
    private fun Scope.spend(): AccountSpendReader = getOrNull<AccountSpendReader>() ?: NoAccountSpendReader()

    override val migrations: List<SchemaMigration> = SharingMigrations

    /** Reads the token endpoints at start, so a malformed one stops the server instead of failing a refresh. */
    override suspend fun initialize(koin: Koin) {
        SharingConfig.tokenEndpoints(koin.get<ConfigSource>())
    }

    override fun routes(route: Route, koin: Koin) {
        route.sharingRoutes(koin.get())
    }
}
