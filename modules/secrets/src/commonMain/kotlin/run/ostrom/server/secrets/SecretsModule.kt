// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.CreateSecretUseCase
import run.ostrom.server.secrets.domain.CreateSecretUseCaseImpl
import run.ostrom.server.secrets.domain.DeleteSecretUseCase
import run.ostrom.server.secrets.domain.DeleteSecretUseCaseImpl
import run.ostrom.server.secrets.domain.FindSecretUseCase
import run.ostrom.server.secrets.domain.FindSecretUseCaseImpl
import run.ostrom.server.secrets.domain.ListSecretsUseCase
import run.ostrom.server.secrets.domain.ListSecretsUseCaseImpl
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import run.ostrom.server.secrets.domain.RevealSecretsUseCaseImpl
import run.ostrom.server.secrets.domain.RotateSecretUseCase
import run.ostrom.server.secrets.domain.RotateSecretUseCaseImpl
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.domain.SecretDirectoryUseCase
import run.ostrom.server.secrets.domain.SecretDirectoryUseCaseImpl
import run.ostrom.server.secrets.domain.SecretsRepository
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.SecretsKey
import run.ostrom.server.secrets.infrastructure.SecretsMigrations
import run.ostrom.server.secrets.infrastructure.SecretsRepositoryImpl
import run.ostrom.server.secrets.presentation.SecretsRoutesDependencies
import run.ostrom.server.secrets.presentation.secretsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The secrets module: the org's encrypted secrets, revealed only to server-side consumers (MCP relay, inference
 * gateway). Callers are resolved by the [CallerResolver] identity binds; without one, every call is refused.
 */
object SecretsModule : ServerModule {
    override val name: String = "secrets"

    override val koinModule: Module = module {
        single<SecretCipher> {
            AesGcmSecretCipher(SecretsKey.load(get<ConfigSource>()))
        }
        single<SecretsRepository> { SecretsRepositoryImpl(get()) }
        single<CreateSecretUseCase> { CreateSecretUseCaseImpl(get(), get(), get()) }
        single<ListSecretsUseCase> { ListSecretsUseCaseImpl(get()) }
        single<RotateSecretUseCase> { RotateSecretUseCaseImpl(get(), get(), get()) }
        single<DeleteSecretUseCase> { DeleteSecretUseCaseImpl(get()) }
        single<RevealSecretsUseCase> { RevealSecretsUseCaseImpl(get(), get()) }
        single<FindSecretUseCase> { FindSecretUseCaseImpl(get()) }
        single<SecretDirectoryUseCase> { SecretDirectoryUseCaseImpl(get()) }
        single {
            SecretsRoutesDependencies(
                getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = SecretsMigrations

    override suspend fun initialize(koin: Koin) {
        // Loads (or creates) the key at start: a missing or malformed key stops the server now, not at the first use.
        koin.get<SecretCipher>()
    }

    override fun routes(route: Route, koin: Koin) {
        route.secretsRoutes(koin.get())
    }

    /** Organization secrets. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.SECRETS)
}
