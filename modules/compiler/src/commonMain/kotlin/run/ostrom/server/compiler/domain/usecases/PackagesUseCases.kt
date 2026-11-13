// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.usecases

import run.ostrom.core.api.models.PackageMode
import run.ostrom.server.compiler.domain.models.CatalogEntry
import run.ostrom.server.compiler.domain.models.CompileInputs
import run.ostrom.server.compiler.domain.models.compiled
import run.ostrom.server.compiler.domain.models.PackageAudit
import run.ostrom.server.compiler.domain.models.PackagePin
import run.ostrom.server.compiler.domain.models.StoredPackage
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.services.CompiledBody
import run.ostrom.server.compiler.domain.services.CompiledPackages
import run.ostrom.server.compiler.domain.services.CompilerSources
import run.ostrom.server.compiler.domain.services.GatesCatalog
import run.ostrom.server.compiler.domain.services.HarnessReceiptSigner
import run.ostrom.server.compiler.domain.services.HarnessStamp
import run.ostrom.server.compiler.domain.services.JsText
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.domain.services.PackageSigner
import run.ostrom.server.compiler.domain.services.RulesHarness
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A package as served: the stored version and whether it is the repository's pinned one.
 *
 * @property stored The version.
 * @property pinned True when sessions of the repository receive it.
 */
data class ServedPackage(val stored: StoredPackage, val pinned: Boolean) {
    /** The envelope as served, `pinned` as it is now. */
    val envelope get() = stored.envelope.copy(pinned = pinned)
}

/** Compiles an organization's sources for a repository (shared by publish, preview and the gates catalogue). */
interface CompileSourcesUseCase {
    /**
     * Reads the sources of a compilation.
     *
     * @param orgId The organization.
     * @param repo The repository, or null for the organization.
     * @return The inputs, at the clock's time.
     */
    suspend fun inputs(orgId: Uuid, repo: String?): CompileInputs

    /**
     * Compiles, giving the harness the version its digest already has (or a new signed one).
     *
     * @param orgId The organization.
     * @param inputs The inputs.
     * @return The body.
     */
    suspend fun compile(orgId: Uuid, inputs: CompileInputs): CompiledBody
}

/**
 * [CompileSourcesUseCase] on the repository and the sources of the other domains.
 *
 * @param repository The compiler's storage.
 * @param sources Rules, repositories and settings.
 * @param harnessReceipts Signs a new harness version.
 * @param clock The time.
 * @param packages The packages already compiled, by their inputs: the same inputs are compiled once.
 */
class CompileSourcesUseCaseImpl(
    private val repository: CompilerRepository,
    private val sources: CompilerSources,
    private val harnessReceipts: HarnessReceiptSigner,
    private val clock: Clock,
    private val packages: CompiledPackages = CompiledPackages(),
) : CompileSourcesUseCase {
    override suspend fun inputs(orgId: Uuid, repo: String?): CompileInputs {
        val settings = sources.settings(orgId)
        return CompileInputs(
            repo = repo,
            serverUrl = "",
            workflows = repository.workflows(orgId).compiled(),
            rules = sources.rules(orgId),
            decisions = repository.lastDecisions(orgId) + sources.ruleDecisions(orgId),
            repos = sources.repos(orgId),
            prodApprover = settings.prodApprover,
            codePublishers = settings.codePublishers,
            now = clock.now(),
            ruleGates = sources.ruleGates(orgId),
        )
    }

    override suspend fun compile(orgId: Uuid, inputs: CompileInputs): CompiledBody =
        packages.of(orgId, inputs) {
            PackageCompiler.compile(inputs) { digest ->
                repository.harness(orgId, inputs.repo, digest) ?: run {
                    val version = PackageSigner.harnessVersion(inputs.now, digest)
                    val rules = RulesHarness(inputs.rules, inputs.decisions, inputs.repos).rulesFor(inputs.repo)
                    val receipt = harnessReceipts.sign(orgId, version, digest, rules, inputs.now)
                    repository.saveHarness(orgId, inputs.repo, digest, HarnessStamp(version, receipt))
                }
            }
        }
}

/** Compiles, stores and pins the package of a repository. */
interface PublishPackageUseCase {
    /**
     * Publishes: a new content is signed once and stored; the pin follows it (`auto`) or stays (`manual`).
     *
     * @param orgId The organization.
     * @param repo The repository, or null for the organization.
     * @param serverUrl The address sessions call back.
     * @param actor Who asks.
     * @param byAgent Whether an agent session asks: under a manual pin it never sets one (second audit L1).
     * @return The package the repository's sessions receive.
     */
    suspend operator fun invoke(
        orgId: Uuid,
        repo: String?,
        serverUrl: String,
        actor: String,
        byAgent: Boolean = false,
    ): ServedPackage
}

/**
 * [PublishPackageUseCase] as the prototype's `publish`.
 *
 * @param compiler Compiles.
 * @param repository The compiler's storage.
 * @param sources The settings.
 * @param signer The instance key.
 * @param clock The time.
 */
class PublishPackageUseCaseImpl(
    private val compiler: CompileSourcesUseCase,
    private val repository: CompilerRepository,
    private val sources: CompilerSources,
    private val signer: InstanceSigner,
    private val clock: Clock,
) : PublishPackageUseCase {
    override suspend fun invoke(
        orgId: Uuid,
        repo: String?,
        serverUrl: String,
        actor: String,
        byAgent: Boolean,
    ): ServedPackage {
        val inputs = compiler.inputs(orgId, repo).copy(serverUrl = serverUrl)
        val body = compiler.compile(orgId, inputs)
        val stored = repository.findPackage(orgId, body.contentDigest) ?: run {
            val envelope = PackageSigner.sign(body, inputs.now, signer, pinned = false)
            val saved = repository.savePackage(
                orgId,
                StoredPackage(envelope, JsText.iso(inputs.now), countsOf(body)),
            )
            audit(orgId, "package.compiled", actor, saved, saved.counts.mapValues { JsonPrimitive(it.value) })
            saved
        }
        val read = repository.pin(orgId, repo)
        val manual = sources.settings(orgId).manualPin
        // A manual pin is a person's choice: an agent starting a session reads it and never sets the first one.
        val moves = if (manual) read == null && !byAgent else read?.contentDigest != stored.envelope.contentDigest
        val pin = if (!stored.revoked && moves) {
            val by = if (manual) actor else AUTO_PIN
            PackagePin(stored.envelope.contentDigest, by, clock.now()).also {
                repository.setPin(orgId, repo, it)
                audit(orgId, "package.pinned", by, stored, emptyMap())
            }
        } else {
            read
        }
        // The pin read once, and its package read only when it is not the one just compiled (perf pass of the launch).
        val current = when (val digest = pin?.contentDigest) {
            null, stored.envelope.contentDigest -> stored
            else -> repository.findPackage(orgId, digest) ?: stored
        }
        return ServedPackage(current, pin?.contentDigest == current.envelope.contentDigest)
    }

    private suspend fun audit(
        orgId: Uuid,
        event: String,
        actor: String,
        stored: StoredPackage,
        meta: Map<String, JsonPrimitive>,
    ) = PackageAuditor(repository, clock).record(orgId, event, actor, stored, meta)

    private fun countsOf(body: CompiledBody): Map<String, Int> = mapOf(
        "workflows" to body.workflows.size,
        "nodes" to body.nodes.size,
        "hooks" to body.hooks.size,
        "scripts" to body.scripts.size,
        "enforcedScripts" to body.scripts.count { it.mode == PackageMode.ENFORCE },
        "notifications" to body.notifications.size,
        "files" to body.files.size,
    )

    private companion object {
        const val AUTO_PIN = "compiler (auto pin)"
    }
}

// An audit line about a package: the version, its repository and digest, and the meta of the event.
internal class PackageAuditor(private val repository: CompilerRepository, private val clock: Clock) {
    suspend fun record(
        orgId: Uuid,
        event: String,
        actor: String,
        stored: StoredPackage,
        meta: Map<String, JsonPrimitive>,
    ) {
        val details = buildJsonObject {
            put("repo", JsonPrimitive(stored.envelope.repo))
            put("digest", stored.envelope.digest)
            meta.forEach { (key, value) -> put(key, value) }
        }
        val id = "aud_${Uuid.random().toHexString().take(AUDIT_ID)}"
        val line = PackageAudit(id, JsText.iso(clock.now()), event, actor, stored.envelope.version, details)
        repository.audit(orgId, line)
    }
}

private const val AUDIT_ID = 12

/** Revokes or restores a package version. */
interface RevokePackageUseCase {
    /**
     * Revokes: launchers refuse it, and it stops being the pinned version.
     *
     * @param orgId The organization.
     * @param digest Its envelope or content digest.
     * @param by Who revokes.
     * @param reason Why.
     * @return The package.
     */
    suspend fun revoke(orgId: Uuid, digest: String, by: String, reason: String): StoredPackage

    /**
     * Restores a revoked version (it is not pinned again by itself).
     *
     * @param orgId The organization.
     * @param digest Its envelope or content digest.
     * @param by Who restores.
     * @return The package.
     */
    suspend fun restore(orgId: Uuid, digest: String, by: String): StoredPackage
}

/**
 * [RevokePackageUseCase] on the repository.
 *
 * @param repository The compiler's storage.
 * @param clock The time.
 */
class RevokePackageUseCaseImpl(
    private val repository: CompilerRepository,
    clock: Clock,
) : RevokePackageUseCase {
    private val auditor = PackageAuditor(repository, clock)

    override suspend fun revoke(orgId: Uuid, digest: String, by: String, reason: String): StoredPackage {
        val stored = find(orgId, digest)
        val next = stored.copy(envelope = stored.envelope.copy(revoked = true), revokedBy = by, revokeReason = reason)
        repository.updatePackage(orgId, next)
        val repo = stored.envelope.repo
        if (repository.pin(orgId, repo)?.contentDigest == stored.envelope.contentDigest) {
            repository.setPin(orgId, repo, null)
        }
        auditor.record(orgId, "package.revoked", by, next, mapOf("reason" to JsonPrimitive(reason)))
        return next
    }

    override suspend fun restore(orgId: Uuid, digest: String, by: String): StoredPackage {
        val stored = find(orgId, digest)
        val next = stored.copy(envelope = stored.envelope.copy(revoked = false), revokedBy = null, revokeReason = null)
        repository.updatePackage(orgId, next)
        auditor.record(orgId, "package.restored", by, next, emptyMap())
        return next
    }

    private suspend fun find(orgId: Uuid, digest: String): StoredPackage =
        repository.findPackage(orgId, digest) ?: throw OstromException(ErrorKind.NOT_FOUND, "Package not found.")
}

/** The gates catalogue of a repository: which gate covers which action. */
interface GatesCatalogUseCase {
    /**
     * Resolves the catalogue.
     *
     * @param orgId The organization.
     * @param repo The repository, or null for the organization.
     * @return The catalogue, in order.
     */
    suspend operator fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry>

    /**
     * The catalogue of the organization and of every repository a gate is limited to, from the same reads.
     *
     * @param orgId The organization.
     * @return Each catalogue by repository, null for the organization's.
     */
    suspend fun everywhere(orgId: Uuid): Map<String?, List<CatalogEntry>> = mapOf(null to invoke(orgId, null))
}

/**
 * [GatesCatalogUseCase] on the saved workflows and the gates of the rules: three reads, no compilation (a gate asks
 * it on every boundary action).
 *
 * @param repository The workflows.
 * @param sources The production approver and the gates of the rules.
 */
class GatesCatalogUseCaseImpl(
    private val repository: CompilerRepository,
    private val sources: CompilerSources,
) : GatesCatalogUseCase {
    override suspend fun invoke(orgId: Uuid, repo: String?): List<CatalogEntry> = catalogue(orgId).catalog(repo)

    override suspend fun everywhere(orgId: Uuid): Map<String?, List<CatalogEntry>> {
        val catalogue = catalogue(orgId)
        return (listOf(null) + catalogue.repositories()).associateWith { catalogue.catalog(it) }
    }

    private suspend fun catalogue(orgId: Uuid): GatesCatalog {
        val docs = repository.workflows(orgId).compiled()
        return GatesCatalog.of(docs, sources.ruleGates(orgId), sources.settings(orgId).prodApprover)
    }
}

/** What a session of a repository would receive, compiled now and not signed. */
interface PreviewPackageUseCase {
    /**
     * Compiles without storing nor signing.
     *
     * @param orgId The organization.
     * @param repo The repository.
     * @param serverUrl The address sessions call back.
     * @return The session package.
     */
    suspend operator fun invoke(orgId: Uuid, repo: String?, serverUrl: String): JsonObject
}

/**
 * [PreviewPackageUseCase] with the harness version a digest already has.
 *
 * @param compiler Compiles.
 */
class PreviewPackageUseCaseImpl(private val compiler: CompileSourcesUseCase) : PreviewPackageUseCase {
    override suspend fun invoke(orgId: Uuid, repo: String?, serverUrl: String): JsonObject {
        val inputs = compiler.inputs(orgId, repo).copy(serverUrl = serverUrl)
        val body = compiler.compile(orgId, inputs)
        val contentDigest = body.contentDigest
        return JsonObject(
            body.body + mapOf(
                "version" to JsonPrimitive(PackageSigner.packageVersion(inputs.now, contentDigest)),
                "contentDigest" to JsonPrimitive(contentDigest),
                "compiledAt" to JsonPrimitive(JsText.iso(inputs.now)),
            ),
        )
    }
}
