// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A repository's languages, counted by file extension, and the build tools its root files name. */
object BuildFacts {
    private val languages = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin", "java" to "Java", "ts" to "TypeScript", "tsx" to "TypeScript",
        "js" to "JavaScript", "jsx" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript", "py" to "Python",
        "go" to "Go", "rs" to "Rust", "swift" to "Swift", "rb" to "Ruby", "php" to "PHP", "cs" to "C#", "c" to "C",
        "h" to "C", "cpp" to "C++", "hpp" to "C++", "m" to "Objective-C", "sh" to "Shell", "dart" to "Dart",
        "vue" to "Vue", "svelte" to "Svelte", "scala" to "Scala", "ex" to "Elixir", "exs" to "Elixir", "sql" to "SQL",
        "tf" to "HCL",
    )

    // The package managers by lockfile, in the order a repository holding two is read.
    private val lockfiles = listOf(
        "pnpm" to "pnpm-lock.yaml",
        "Yarn" to "yarn.lock",
        "Bun" to "bun.lock",
        "Bun" to "bun.lockb",
        "npm" to "package-lock.json",
    )
    private val checkScripts = listOf("lint", "typecheck", "test")

    /**
     * The languages of the code files, most used first.
     *
     * @param paths The repository's files.
     * @return Each language with its file count.
     */
    fun languages(paths: List<String>): List<LanguageShare> = paths.asSequence()
        .filterNot(RepositoryFacts::vendored)
        .mapNotNull { languages[it.substringAfterLast('/').substringAfterLast('.', "").lowercase()] }
        .groupingBy { it }
        .eachCount()
        .map { (name, files) -> LanguageShare(name, files) }
        .sortedWith(compareByDescending<LanguageShare> { it.files }.thenBy { it.name })

    /**
     * The build tools at the root, each with the command that checks a change when the repository names one.
     *
     * @param paths The repository's files.
     * @param packageJson The root `package.json`, when read.
     * @return The tools; `Make` only when nothing else is found.
     */
    fun buildTools(paths: List<String>, packageJson: String?): List<BuildTool> {
        val root = paths.filter { '/' !in it }.toSet()
        val tools = listOfNotNull(
            gradle(root),
            maven(root),
            node(root, packageJson),
            tool(root, "Cargo.toml", "Cargo", "cargo test", "Cargo.lock"),
            tool(root, "go.mod", "Go", "go test ./...", "go.sum"),
            python(paths, root),
        )
        return tools.ifEmpty { listOfNotNull(tool(root, "Makefile", "Make", null, null)) }
    }

    private fun gradle(root: Set<String>): BuildTool? {
        val build = listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")
            .firstOrNull { it in root }
        return when {
            "gradlew" in root -> BuildTool("Gradle", "./gradlew check", listOfNotNull("gradlew", build), true)
            build != null -> BuildTool("Gradle", "gradle check", listOf(build), false)
            else -> null
        }
    }

    private fun maven(root: Set<String>): BuildTool? = when {
        "pom.xml" !in root -> null
        "mvnw" in root -> BuildTool("Maven", "./mvnw verify", listOf("mvnw", "pom.xml"), true)
        else -> BuildTool("Maven", "mvn verify", listOf("pom.xml"), false)
    }

    private fun node(root: Set<String>, packageJson: String?): BuildTool? {
        if (RepositoryFacts.PACKAGE_JSON !in root) return null
        val (manager, lockfile) = lockfiles.firstOrNull { it.second in root } ?: ("npm" to null)
        val scripts = scripts(packageJson)
        val command = checkScripts.filter { it in scripts }.joinToString(" && ") { script(manager, it) }
        val evidence = listOfNotNull(lockfile, RepositoryFacts.PACKAGE_JSON)
        return BuildTool(manager, command.ifEmpty { null }, evidence, lockfile != null)
    }

    private fun script(manager: String, name: String): String = when {
        manager == "npm" && name == "test" -> "npm test"
        // `bun test` runs Bun's own test runner, not the script: `bun run test` runs the script.
        manager == "npm" || manager == "Bun" -> "${manager.lowercase()} run $name"
        else -> "${manager.lowercase()} $name"
    }

    private fun scripts(packageJson: String?): Set<String> {
        val parsed = packageJson?.let(::parse)
        return ((parsed as? JsonObject)?.get("scripts") as? JsonObject)?.keys.orEmpty()
    }

    // A manifest that is not JSON names no script; the tool is still found by its file.
    private fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun python(paths: List<String>, root: Set<String>): BuildTool? {
        val manifest = listOf("pyproject.toml", "requirements.txt", "setup.py").firstOrNull { it in root }
            ?: return null
        val tested = paths.any { it.startsWith("tests/") || it.substringAfterLast('/').startsWith("test_") }
        val (name, runner, lock) = when {
            "uv.lock" in root -> Triple("uv", "uv run pytest", "uv.lock")
            "poetry.lock" in root -> Triple("Poetry", "poetry run pytest", "poetry.lock")
            else -> Triple("pip", "python -m pytest", null)
        }
        return BuildTool(name, runner.takeIf { tested }, listOfNotNull(lock, manifest), lock != null)
    }

    private fun tool(root: Set<String>, file: String, name: String, command: String?, lock: String?): BuildTool? {
        if (file !in root) return null
        val locked = lock != null && lock in root
        return BuildTool(name, command, listOfNotNull(lock?.takeIf { locked }, file), locked)
    }
}
