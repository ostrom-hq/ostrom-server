// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.domain.services

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.launches.domain.models.Launcher
import kotlin.time.Duration
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
