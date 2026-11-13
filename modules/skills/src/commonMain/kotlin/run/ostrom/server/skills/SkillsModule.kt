// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.skills.domain.ManageSkillCatalogUseCase
import run.ostrom.server.skills.domain.ManageSkillCatalogUseCaseImpl
import run.ostrom.server.skills.domain.PublishSkillVersionUseCase
import run.ostrom.server.skills.domain.PublishSkillVersionUseCaseImpl
import run.ostrom.server.skills.domain.Sha256
import run.ostrom.server.skills.domain.SkillDeliveryUseCase
import run.ostrom.server.skills.domain.SkillDeliveryUseCaseImpl
import run.ostrom.server.skills.domain.SkillsRepository
import run.ostrom.server.skills.infrastructure.OpenSslSha256
import run.ostrom.server.skills.infrastructure.SkillsMigrations
import run.ostrom.server.skills.infrastructure.SkillsRepositoryImpl
import run.ostrom.server.skills.presentation.SkillsRoutesDependencies
import run.ostrom.server.skills.presentation.skillsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The team skills module (TeamCode's `docs/skills.md`): a versioned, immutable catalog of skills with SHA-256
 * digests, published from the console or by a CI's service key, pinned, delivered to all members or to members set
 * to yes, and synced by the CLI to the members' workstations, which report what they installed. Members also publish
 * personal skills only they receive, which an owner or an admin can promote to the team.
 */
object SkillsModule : ServerModule {
    override val name: String = "skills"

    override val koinModule: Module = module {
        single<SkillsRepository> { SkillsRepositoryImpl(get()) }
        single<Sha256> { OpenSslSha256() }
        single<PublishSkillVersionUseCase> { PublishSkillVersionUseCaseImpl(get(), get(), get(), get()) }
        single<ManageSkillCatalogUseCase> { ManageSkillCatalogUseCaseImpl(get(), get(), get(), get()) }
        single<SkillDeliveryUseCase> { SkillDeliveryUseCaseImpl(get(), get(), get(), get()) }
        single { SkillsRoutesDependencies(get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = SkillsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.skillsRoutes(koin.get())
    }

    /** The skill catalog and its delivery. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.SKILLS)
}
