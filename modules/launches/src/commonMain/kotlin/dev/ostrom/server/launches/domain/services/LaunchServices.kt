// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.services

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.launches.domain.models.HooksCheck
import dev.ostrom.server.launches.domain.models.Launcher
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Turns a person's credential into the [Launcher] it acts as (the identity and orgs modules, bound here). */
interface LauncherResolver {
    /**
     * Resolves the person and their organization.
     *
     * @param principal The authenticated principal.
     *
     * @return The launcher, or null when the credential is not a person's member of an organization.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal): Launcher?

    /**
     * Reads a person's membership of an organization now: an agent token acts only while its person still may.
     *
     * @param orgId The organization.
     * @param userId The person.
     *
     * @return Their membership (member id and role), or null when they are no longer a member.
     */
    suspend fun member(orgId: Uuid, userId: Uuid): OrgPrincipal?

    /**
     * Resolves a member as the person who launches: the owner of a cloud session, whose runner opens the launch.
     *
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return The launcher, or null when the member is no longer one.
     */
    suspend fun owner(orgId: Uuid, memberId: Uuid): Launcher?

    /**
     * Resolves another developer of an organization by their login, as the person they would launch as.
     *
     * @param orgId The organization.
     * @param login Their login.
     *
     * @return The launcher, or null when nobody of that login is a member of [orgId].
     */
    suspend fun colleague(orgId: Uuid, login: String): Launcher?
}

/** Issues the agent token of a launch (the identity module's agent tokens). */
interface AgentTokenService {
    /**
     * Issues the token: its subject is the launch, its only scope acting on the launch's sessions (audit A2).
     *
     * @param launcher The person and organization it acts for.
     * @param launchId The launch.
     * @param lifetime How long it lives.
     *
     * @return The token's secret, handed to the CLI once.
     */
    suspend fun issue(launcher: Launcher, launchId: String, lifetime: Duration): String

    /**
     * Revokes the agent tokens of a launch, at once: every route that accepts one refuses them from then on.
     *
     * @param launchId The launch.
     *
     * @return How many were revoked: 0 when already revoked.
     */
    suspend fun revoke(launchId: String): Long
}

/**
 * What the launcher is told about the hooks of a launch: the organization's wait (the orgs module's setting) and
 * whether a gate it enforces would be skipped by an agent without hooks (the gates module's catalogue).
 */
fun interface HooksCheckRules {
    /**
     * Reads the rules for a launch.
     *
     * @param orgId The launch's organization.
     * @param repo The launch's repository, or null.
     *
     * @return How long to wait for the first hook, and whether to stop the agent when none came.
     */
    suspend fun check(orgId: Uuid, repo: String?): HooksCheck
}

/**
 * The organization's rules on models, which the inference gateway owns. With the gateway loaded, they are its model
 * policy ([dev.ostrom.server.launches.infrastructure.ModelAccessLaunchRules]): a launch is refused what the gateway
 * would refuse. Without it, every model is allowed at launch
 * ([dev.ostrom.server.launches.infrastructure.NoLaunchModelRules]).
 */
interface LaunchModelRules {
    /**
     * Says why a person may not launch with a model.
     *
     * @param launcher The person and their organization.
     * @param model The model the launch names.
     *
     * @return The refusal, or null when the model is allowed.
     */
    suspend fun refusal(launcher: Launcher, model: String): String?
}

/**
 * Whether the launch a cloud session's runner opened still belongs to the session's current execution
 * (AUDIT-SECU-RUNNER-2 R2): its agent token acts only while that execution runs, never after the session is paused,
 * deleted or restarted, whatever the token's own expiry.
 */
fun interface CloudExecutions {
    /**
     * Tells whether the execution is current.
     *
     * @param orgId The launch's organization.
     * @param cloudSessionId The cloud session that opened the launch.
     * @param openedAt When the launch was opened.
     *
     * @return True while the session holds the execution that was running when the launch was opened.
     */
    suspend fun current(orgId: Uuid, cloudSessionId: Uuid, openedAt: Instant): Boolean
}
