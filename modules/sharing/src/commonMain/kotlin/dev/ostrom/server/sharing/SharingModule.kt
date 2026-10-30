package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.UnresolvedCallerResolver
import dev.ostrom.server.sharing.domain.AccountSpendReader
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import dev.ostrom.server.sharing.domain.NoAccountSpendReader
import dev.ostrom.server.sharing.domain.OAuthRefresher
import dev.ostrom.server.sharing.domain.QuotaHeaderParser
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
import dev.ostrom.server.sharing.infrastructure.SharedAccountRepositoryImpl
import dev.ostrom.server.sharing.infrastructure.SharingMigrations
import dev.ostrom.server.sharing.presentation.SharingRoutesDependencies
import dev.ostrom.server.sharing.presentation.sharingRoutes
import io.ktor.client.HttpClient
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
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
        single { AccountTokenCipher(get()) }
        single<OAuthRefresher> { HttpOAuthRefresher(get(HTTP_CLIENT), get()) }
        single<QuotaHeaderParser> { ProviderQuotaHeaderParser() }
        single { SharedAccountPool(get(), get(), get(), get(), getOrNull<AccountSpendReader>() ?: NoAccountSpendReader(), get()) }
        single<ShareAccountUseCase> { ShareAccountUseCaseImpl(get(), get(), get()) }
        single<ListSharedAccountsUseCase> {
            ListSharedAccountsUseCaseImpl(get(), get(), getOrNull<AccountSpendReader>() ?: NoAccountSpendReader(), get())
        }
        single<UpdateSharedAccountUseCase> { UpdateSharedAccountUseCaseImpl(get()) }
        single<RemoveSharedAccountUseCase> { RemoveSharedAccountUseCaseImpl(get()) }
        single { SharingRoutesDependencies(getOrNull<CallerResolver>() ?: UnresolvedCallerResolver(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SharingMigrations

    override fun routes(route: Route, koin: Koin) {
        route.sharingRoutes(koin.get())
    }
}
