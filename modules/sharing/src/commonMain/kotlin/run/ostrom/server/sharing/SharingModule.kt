// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.sharing.domain.AccountGroups
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.AccountSpendReader
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.CredentialSecrets
import run.ostrom.server.sharing.domain.EnrollSharedAccountUseCase
import run.ostrom.server.sharing.domain.EnrollSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.GroupMembersUseCase
import run.ostrom.server.sharing.domain.GroupMembersUseCaseImpl
import run.ostrom.server.sharing.domain.GroupRepository
import run.ostrom.server.sharing.domain.GroupsUseCase
import run.ostrom.server.sharing.domain.GroupsUseCaseImpl
import run.ostrom.server.sharing.domain.HandOverSharedAccountUseCase
import run.ostrom.server.sharing.domain.HandOverSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCaseImpl
import run.ostrom.server.sharing.domain.MemberNames
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.NoMemberLogins
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.QuotaHeaderParser
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import run.ostrom.server.sharing.domain.RemoveSharedAccountUseCase
import run.ostrom.server.sharing.domain.RemoveSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.ShareAccountUseCase
import run.ostrom.server.sharing.domain.ShareAccountUseCaseImpl
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCaseImpl
import run.ostrom.server.sharing.domain.ViewSharedAccountUseCase
import run.ostrom.server.sharing.domain.ViewSharedAccountUseCaseImpl
import run.ostrom.server.sharing.infrastructure.AccountGroupsImpl
import run.ostrom.server.sharing.infrastructure.AccountRefreshRepositoryImpl
import run.ostrom.server.sharing.infrastructure.AccountSlotRepositoryImpl
import run.ostrom.server.sharing.infrastructure.GroupRepositoryImpl
import run.ostrom.server.sharing.infrastructure.HttpOAuthRefresher
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import run.ostrom.server.sharing.infrastructure.QuotaWindowRepositoryImpl
import run.ostrom.server.sharing.infrastructure.SecretsCredentialSecrets
import run.ostrom.server.sharing.infrastructure.SharedAccountRepositoryImpl
import run.ostrom.server.sharing.infrastructure.SharingMigrations
import run.ostrom.server.sharing.presentation.GroupRoutesDependencies
import run.ostrom.server.sharing.presentation.SharingRoutesDependencies
import run.ostrom.server.sharing.presentation.groupRoutes
import run.ostrom.server.sharing.presentation.sharingRoutes
import io.ktor.client.HttpClient
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.dsl.onClose

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
        } onClose { it?.close() } // Its selector holds a thread of Dispatchers.IO until then (StoppedApplicationsTest).
        single<SharedAccountRepository> { SharedAccountRepositoryImpl(get()) }
        single<QuotaWindowRepository> { QuotaWindowRepositoryImpl(get()) }
        single { AccountTokenCipher(get()) }
        // The token endpoints an administrator sets in the console (the admin module collects every bound section).
        single<SettingsSection>(named(SharingSettings.SECTION.name)) { SharingSettings.SECTION }
        single<OAuthRefresher> {
            val settings = get<InstanceSettings>()
            HttpOAuthRefresher(
                get(HTTP_CLIENT),
                get(),
                { SharingSettings.tokenEndpoints(settings.read(SharingSettings.SECTION.name)) },
            )
        }
        single<QuotaHeaderParser> { ProviderQuotaHeaderParser() }
        single<AccountRefreshRepository> { AccountRefreshRepositoryImpl(get()) }
        single { AccountTokens(get(), get(), get(), get(), get()) }
        single<AccountGroups> { AccountGroupsImpl(get()) }
        single<AccountSlotRepository> { AccountSlotRepositoryImpl(get()) }
        single { SharedAccountPool(get(), get(), get(), get(), get(), spend(), get(), get()) }
        single<ShareAccountUseCase> { ShareAccountUseCaseImpl(get(), get(), get()) }
        single { MemberNames(logins(), get()) }
        single<ListSharedAccountsUseCase> {
            ListSharedAccountsUseCaseImpl(get(), get(), get(), spend(), get(), get())
        }
        single<UpdateSharedAccountUseCase> { UpdateSharedAccountUseCaseImpl(get(), get()) }
        single<ViewSharedAccountUseCase> { ViewSharedAccountUseCaseImpl(get()) }
        single<CredentialSecrets> { SecretsCredentialSecrets(get(), get()) }
        single<EnrollSharedAccountUseCase> { EnrollSharedAccountUseCaseImpl(get(), get(), get(), get()) }
        single<RemoveSharedAccountUseCase> { RemoveSharedAccountUseCaseImpl(get()) }
        single<HandOverSharedAccountUseCase> { HandOverSharedAccountUseCaseImpl(get()) }
        single<GroupRepository> { GroupRepositoryImpl(get()) }
        single<GroupsUseCase> { GroupsUseCaseImpl(get(), get(), logins(), get()) }
        single<GroupMembersUseCase> { GroupMembersUseCaseImpl(get(), logins()) }
        single {
            GroupRoutesDependencies(getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()), get(), get())
        }
        single {
            SharingRoutesDependencies(
                getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }

    /** The orgs module's logins when it is loaded; none otherwise (people are then named by id). */
    private fun Scope.logins(): MemberLogins = getOrNull<MemberLogins>() ?: NoMemberLogins

    /** The usage ledger's spend per account when the gateway binds it; none otherwise. */
    private fun Scope.spend(): AccountSpendReader = getOrNull<AccountSpendReader>() ?: NoAccountSpendReader()

    override val migrations: List<SchemaMigration> = SharingMigrations

    override fun routes(route: Route, koin: Koin) {
        route.sharingRoutes(koin.get())
        route.groupRoutes(koin.get())
    }

    /** Shared accounts, their capacity and the groups they are shared with, and their refresh-ownership handover. */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(ServerFeatures.SHARING, ServerFeatures.SHARING_HANDOVER)
}
