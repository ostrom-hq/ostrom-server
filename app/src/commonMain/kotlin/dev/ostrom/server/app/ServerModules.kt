package dev.ostrom.server.app

import dev.ostrom.server.artifacts.ArtifactsModule
import dev.ostrom.server.automations.AutomationsModule
import dev.ostrom.server.budgets.BudgetsModule
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gateway.GatewayModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.integrations.github.GitHubIntegrationModule
import dev.ostrom.server.integrations.slack.SlackIntegrationModule
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.mcprelay.McpRelayModule
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.sharing.SharingModule

/**
 * Every functional module, in dependency order: migrations and initialization run in this order, so a module comes
 * after the modules whose tables it references. Each module owner adds or edits only its own line.
 *
 * @return The modules.
 */
fun serverModules(): List<ServerModule> = listOf(
    InstanceModule,
    IdentityModule,
    OrgsModule,
    SessionsModule,
    GatesModule,
    ReceiptsModule,
    CompilerModule,
    McpRelayModule,
    SecretsModule,
    GatewayModule,
    BudgetsModule,
    RunnersModule,
    AutomationsModule,
    ArtifactsModule,
    GitHubIntegrationModule,
    SlackIntegrationModule,
    SharingModule,
)
