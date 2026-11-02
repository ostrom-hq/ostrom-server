// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

/**
 * The files whose change deserves a human yes, found by their names: secrets committed to the branch, the CI that
 * runs with the repository's secrets, the infrastructure, and the database migrations.
 */
object SensitiveFiles {
    private const val MAX_PREFIXES = 20
    private const val MAX_EVIDENCE = 5

    private val secretNames = setOf(
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials.json", "secrets.json", "secrets.yml",
        "secrets.yaml", ".htpasswd",
    )
    private val secretExtensions = setOf("pem", "key", "p12", "pfx", "jks", "keystore")
    private val envSamples = setOf("example", "sample", "template", "dist", "defaults")
    private val ciDirs = listOf(".github/workflows/", ".circleci/", ".buildkite/")
    private val ciFiles = setOf(".gitlab-ci.yml", "Jenkinsfile", "azure-pipelines.yml", "bitbucket-pipelines.yml")
    private val infraDirs = setOf(
        "terraform", "infra", "infrastructure", "helm", "charts", "k8s", "kubernetes", "ansible", "pulumi",
    )
    private val migrationDirs = setOf("migrations", "migration", "migrate")
    private val flyway = Regex("V\\d+(_\\d+)*__.+\\.sql")

    /**
     * The sensitive files, by kind.
     *
     * @param paths The repository's files.
     * @return One area per kind found, in the order of [AreaKind].
     */
    fun areas(paths: List<String>): List<SensitiveArea> {
        val found = paths.filterNot(RepositoryFacts::vendored).mapNotNull { path -> classify(path)?.let { it to path } }
        return AreaKind.entries.mapNotNull { kind ->
            val hits = found.filter { it.first.first == kind }
            hits.takeIf { it.isNotEmpty() }?.let {
                val prefixes = covering(it.map { hit -> hit.first.second })
                SensitiveArea(
                    kind = kind,
                    prefixes = prefixes.take(MAX_PREFIXES),
                    evidence = it.map { hit -> hit.second }.sorted().take(MAX_EVIDENCE),
                    omitted = prefixes.size - minOf(prefixes.size, MAX_PREFIXES),
                )
            }
        }
    }

    // A file belongs to one kind at most, the first that claims it: a secret under `infra/` is a secret.
    private fun classify(path: String): Pair<AreaKind, String>? {
        val segments = path.split('/')
        val name = segments.last()
        // A file at the root is its own prefix: an empty one would gate nothing.
        val folder = path.dropLast(name.length)
        return secret(path, name)
            ?: ci(path, name)
            ?: directory(segments, migrationDirs, AreaKind.MIGRATIONS)
            ?: (AreaKind.MIGRATIONS to folder.ifEmpty { path }).takeIf { flyway.matches(name) }
            ?: directory(segments, infraDirs, AreaKind.INFRA)
            ?: (AreaKind.INFRA to folder.ifEmpty { path }).takeIf { name.endsWith(".tf") }
    }

    private fun secret(path: String, name: String): Pair<AreaKind, String>? {
        val env = name == ".env" || name.startsWith(".env.") && name.substringAfter(".env.") !in envSamples
        val extension = name.substringAfterLast('.', "").lowercase()
        val secret = env || name in secretNames || extension in secretExtensions
        return (AreaKind.SECRETS to path).takeIf { secret }
    }

    private fun ci(path: String, name: String): Pair<AreaKind, String>? {
        val dir = ciDirs.firstOrNull { path.startsWith(it) }
        return when {
            dir != null -> AreaKind.CI to dir
            path == name && name in ciFiles -> AreaKind.CI to path
            else -> null
        }
    }

    // The directory up to the first segment so named: `src/db/migration/V1.sql` gives `src/db/migration/`.
    private fun directory(segments: List<String>, names: Set<String>, kind: AreaKind): Pair<AreaKind, String>? {
        val at = segments.dropLast(1).indexOfFirst { it.lowercase() in names }
        return if (at < 0) null else kind to segments.take(at + 1).joinToString("/", postfix = "/")
    }

    // The shortest prefixes: `infra/` covers `infra/k8s/`.
    private fun covering(prefixes: List<String>): List<String> {
        val all = prefixes.distinct()
        return all.filter { prefix -> all.none { it != prefix && it.endsWith('/') && prefix.startsWith(it) } }.sorted()
    }
}
