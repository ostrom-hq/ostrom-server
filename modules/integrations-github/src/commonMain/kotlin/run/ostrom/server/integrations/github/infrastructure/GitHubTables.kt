// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [GitHubMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
// only name its columns and their types.

/** The installation of the App per organization (version 1). */
internal object GitHubInstallations : Table("github_installations") {
    val orgId = uuid("org_id")
    val installationId = long("installation_id")
    val account = text("account")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** The single-use states of the install flow (version 1). */
internal object GitHubInstallStates : Table("github_install_states") {
    val stateHash = text("state_hash")
    val orgId = uuid("org_id")
    val createdBy = text("created_by")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(stateHash)
}

/** The check run published per gate (version 1). */
internal object GitHubChecks : Table("github_checks") {
    val orgId = uuid("org_id")
    val gateId = uuid("gate_id")
    val repo = text("repo")
    val sha = text("sha")
    val checkRunId = long("check_run_id")
    val conclusion = text("conclusion").nullable()
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId, gateId)
}

/** An organization's repositories as last read from GitHub (version 2). */
internal object GitHubRepos : Table("github_repos") {
    val orgId = uuid("org_id")
    val name = text("name")
    val description = text("description").nullable()
    val language = text("language").nullable()
    val defaultBranch = text("default_branch")
    val archived = bool("archived")
    val pushedAt = instant("pushed_at").nullable()
    val owners = text("owners")

    override val primaryKey = PrimaryKey(orgId, name)
}

/** An organization's teams as last read from GitHub (version 2). */
internal object GitHubTeams : Table("github_teams") {
    val orgId = uuid("org_id")
    val slug = text("slug")
    val name = text("name")
    val members = text("members")
    val repos = text("repos")

    override val primaryKey = PrimaryKey(orgId, slug)
}

/** When an organization's directory was read and last checked (version 2). */
internal object GitHubDirectorySyncs : Table("github_directory_syncs") {
    val orgId = uuid("org_id")
    val syncedAt = instant("synced_at").nullable()
    val checkedAt = instant("checked_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** The instance's App (versions 2, 5 and 6); `singleton` (the primary key, always true) is never named. */
internal object GitHubApps : Table("github_apps") {
    val appId = long("app_id")
    val slug = text("slug")
    val owner = text("owner").nullable()
    val clientId = text("client_id").nullable()
    val privateKeySealed = text("private_key_sealed")
    val clientSecretSealed = text("client_secret_sealed").nullable()
    val webhookSecretSealed = text("webhook_secret_sealed").nullable()
    val createdBy = text("created_by")
    val createdAt = instant("created_at")
    val version = integer("version").databaseGenerated()
    val updatedBy = text("updated_by")
    val updatedAt = instant("updated_at")
}

/** The single-use states of the manifest flow (version 2). */
internal object GitHubManifestStates : Table("github_manifest_states") {
    val stateHash = text("state_hash")
    val createdBy = text("created_by")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(stateHash)
}

/** Who installed each installation (version 3). */
internal object GitHubInstallationOrigins : Table("github_installation_origins") {
    val installationId = long("installation_id")
    val sender = text("sender")
    val account = text("account")
    val accountType = text("account_type")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(installationId)
}

/** The webhook deliveries handled in the last 30 days (version 3). */
internal object GitHubWebhookDeliveries : Table("github_webhook_deliveries") {
    val deliveryId = text("delivery_id")
    val receivedAt = instant("received_at")

    override val primaryKey = PrimaryKey(deliveryId)
}

/** The members' GitHub links (version 4). */
internal object GitHubLinks : Table("github_links") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val githubUserId = long("github_user_id")
    val githubLogin = text("github_login")
    val clientId = text("client_id")
    val tokensSealed = text("tokens_sealed")
    val state = text("state")
    val accessExpiresAt = instant("access_expires_at").nullable()
    val refreshExpiresAt = instant("refresh_expires_at").nullable()
    val tokenVersion = integer("token_version")
    val refreshPending = bool("refresh_pending")
    val refreshStartedAt = instant("refresh_started_at").nullable()
    val linkedAt = instant("linked_at")
    val refreshedAt = instant("refreshed_at")

    override val primaryKey = PrimaryKey(orgId, memberId)
}

/** The pending attempts to link (version 4). */
internal object GitHubLinkAttempts : Table("github_link_attempts") {
    val orgId = uuid("org_id")
    val id = text("id")
    val memberId = uuid("member_id")
    val kind = text("kind")
    val secretSealed = text("secret_sealed")
    val binding = text("binding").nullable()
    val pollInterval = integer("poll_interval")
    val nextPollAt = instant("next_poll_at")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(orgId, id)
}
