// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudClusterAccess
import run.ostrom.server.cloud.domain.CloudClustersUseCase
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.cloud.domain.WorkspaceStorageClasses
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.runners.domain.CloudClusterObjects
import run.ostrom.server.runners.domain.CollectCloudGarbageUseCaseImpl
import run.ostrom.server.runners.infrastructure.CloudGarbageClusters
import run.ostrom.server.runners.infrastructure.CloudGarbageWorker
import run.ostrom.server.runners.infrastructure.ClusterWorkspaceStorageClasses
import run.ostrom.server.runners.infrastructure.LiveCloudObjectsRepositoryImpl
import run.ostrom.server.runners.domain.BackfillSessionProfilesUseCase
import run.ostrom.server.runners.domain.BackfillSessionProfilesUseCaseImpl
import run.ostrom.server.runners.domain.CatalogSessionProfiles
import run.ostrom.server.runners.domain.KeepPausedSessions
import run.ostrom.server.runners.domain.PausedRetention
import run.ostrom.server.runners.domain.NoSessionProfiles
import run.ostrom.server.runners.domain.SessionProfiles
import run.ostrom.server.runners.infrastructure.CloudKubernetesClusters
import run.ostrom.server.runners.infrastructure.CloudProfileCatalogImpl
import run.ostrom.server.runners.infrastructure.LegacyKubernetesImport
import run.ostrom.server.runners.infrastructure.RunnersConfig.DriverKind.KUBERNETES
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.infrastructure.CLOUD_ACCESS_SETTINGS
import run.ostrom.server.runners.infrastructure.CloudRuntimePausedRetention
import run.ostrom.server.runners.infrastructure.CloudRuntimeRunnerDelivery
import run.ostrom.server.runners.infrastructure.SettingsAllowedOrgs
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.AutomationLimits
import run.ostrom.server.automations.domain.WaitSecretSessions
import run.ostrom.server.automations.domain.WaitSecretSource
import run.ostrom.server.billing.domain.GetAgentUsageUseCase
import run.ostrom.server.billing.domain.GetPlanLimitsUseCase
import run.ostrom.server.cloud.domain.CloudSettingsSource
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.events.SessionNames
import run.ostrom.server.kernel.database.ScheduledJob
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.runners.domain.AllowlistedCloudPlanProvider
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCaseImpl
import run.ostrom.server.runners.domain.RunnerPrincipalResolver
import run.ostrom.server.runners.domain.RunnerTokenPrincipalResolver
import run.ostrom.server.runners.domain.RunnerCredentials
import run.ostrom.server.runners.domain.RunnerCredentialsImpl
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.NoPendingWaitAnswers
import run.ostrom.server.runners.domain.WakeCloudSessionUseCase
import run.ostrom.server.runners.domain.WakeCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCaseImpl
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.CloudConversationService
import run.ostrom.server.runners.domain.CloudConversationServiceImpl
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.ConversationAnnouncement
import run.ostrom.server.runners.infrastructure.CloudConversationMaintenance
import run.ostrom.server.runners.infrastructure.CloudConversationStoreImpl
import run.ostrom.server.runners.presentation.CloudConversationRoutesDependencies
import run.ostrom.server.runners.presentation.cloudConversationRoutes
import run.ostrom.server.runners.domain.RunnerConversationStore
import run.ostrom.server.runners.domain.RunnerConversationUseCase
import run.ostrom.server.runners.domain.RunnerConversationUseCaseImpl
import run.ostrom.server.runners.infrastructure.RunnerConversationStoreImpl
import run.ostrom.server.runners.presentation.RunnerConversationRoutesDependencies
import run.ostrom.server.runners.presentation.runnerConversationRoutes
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.runners.domain.CloudUsageMeter
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.DeleteRunnerUseCase
import run.ostrom.server.runners.domain.DeleteRunnerUseCaseImpl
import run.ostrom.server.runners.domain.ExecutionPlanner
import run.ostrom.server.runners.domain.FreeCloudPlanProvider
import run.ostrom.server.runners.domain.GetCloudSessionUseCase
import run.ostrom.server.runners.domain.GetCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.IssueTerminalTicketUseCase
import run.ostrom.server.runners.domain.IssueTerminalTicketUseCaseImpl
import run.ostrom.server.runners.domain.ListCloudSessionsUseCase
import run.ostrom.server.runners.domain.ListCloudSessionsUseCaseImpl
import run.ostrom.server.runners.domain.ListRegisteredRunnersUseCase
import run.ostrom.server.runners.domain.ListRegisteredRunnersUseCaseImpl
import run.ostrom.server.runners.domain.ListRunnersUseCase
import run.ostrom.server.runners.domain.ListRunnersUseCaseImpl
import run.ostrom.server.runners.domain.MeteredCreateCloudSession
import run.ostrom.server.runners.domain.NoRunnerGitHubTokens
import run.ostrom.server.runners.domain.OpenTerminalUseCase
import run.ostrom.server.runners.domain.OpenTerminalUseCaseImpl
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.RecordCloudSessionActivityUseCase
import run.ostrom.server.runners.domain.RecordCloudSessionActivityUseCaseImpl
import run.ostrom.server.runners.domain.RegisterRunnerUseCase
import run.ostrom.server.runners.domain.RegisterRunnerUseCaseImpl
import run.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.RegisteredRunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RegisteredRunnersRepository
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.RunnerAssignmentsRepository
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCase
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCaseImpl
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerEnvironmentUseCaseImpl
import run.ostrom.server.runners.domain.NoMemberLogins
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCaseImpl
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.RunnerVariables
import run.ostrom.server.runners.domain.RunnerVariablesUseCase
import run.ostrom.server.runners.domain.RunnerVariablesUseCaseImpl
import run.ostrom.server.runners.domain.RunnerWorkSignals
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.SetCloudSessionGitHubAccessUseCase
import run.ostrom.server.runners.domain.SetCloudSessionGitHubAccessUseCaseImpl
import run.ostrom.server.runners.domain.TerminalActivity
import run.ostrom.server.runners.domain.TerminalTickets
import run.ostrom.server.runners.infrastructure.BillingCloudPlanProvider
import run.ostrom.server.runners.infrastructure.BillingCloudUsageMeter
import run.ostrom.server.runners.infrastructure.CloudSessionAutomationLauncher
import run.ostrom.server.runners.infrastructure.CloudSessionWaitSecretSessions
import run.ostrom.server.runners.infrastructure.CloudSettingsBackgroundWorkLimit
import run.ostrom.server.runners.infrastructure.CloudSessionsRepositoryImpl
import run.ostrom.server.runners.infrastructure.CloudSettingsAutomationLimits
import run.ostrom.server.runners.infrastructure.DockerExecutionPlan
import run.ostrom.server.runners.infrastructure.InProcessRunnerWorkSignals
import run.ostrom.server.runners.infrastructure.KtorTerminalForwarder
import run.ostrom.server.runners.infrastructure.ReconcilerWorker
import run.ostrom.server.runners.infrastructure.RegisteredRunnersRepositoryImpl
import run.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import run.ostrom.server.runners.infrastructure.RunnerAssignmentsRepositoryImpl
import run.ostrom.server.runners.infrastructure.RunnerTokensImpl
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.infrastructure.RunnersMigrations
import run.ostrom.server.runners.infrastructure.RuntimeDrivers
import run.ostrom.server.runners.infrastructure.TerminalHostsImpl
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.infrastructure.TerminalReplica
import run.ostrom.server.runners.infrastructure.TerminalTicketsImpl
import run.ostrom.server.runners.infrastructure.replicaHttpClient
import run.ostrom.server.runners.infrastructure.runnerVariables
import run.ostrom.server.runners.presentation.CloudSessionsRoutesDependencies
import run.ostrom.server.runners.domain.CloudInferenceCredentialStore
import run.ostrom.server.runners.domain.CloudSessionWritten
import run.ostrom.server.runners.domain.NoCloudSessionWritten
import run.ostrom.server.runners.domain.RunnerInferenceTokens
import run.ostrom.server.runners.domain.RunnerInferenceUseCaseImpl
import run.ostrom.server.runners.infrastructure.CloudInferenceCredentialStoreImpl
import run.ostrom.server.runners.presentation.RunnerRuntime
import run.ostrom.server.runners.presentation.RunnersRoutesDependencies
import run.ostrom.server.runners.presentation.TerminalRoutesDependencies
import run.ostrom.server.runners.presentation.cloudSessionsRoutes
import run.ostrom.server.runners.presentation.cloudTransfersRoutes
import run.ostrom.server.runners.presentation.CloudTransfersRoutesDependencies
import run.ostrom.server.runners.infrastructure.CloudTransfersRepositoryImpl
import run.ostrom.server.runners.infrastructure.MemberTransferRecipients
import run.ostrom.server.runners.infrastructure.RuntimeMemberTransferPolicy
import run.ostrom.server.runners.domain.CloudTransfersUseCaseImpl
import run.ostrom.server.runners.domain.CompleteCloudTransferUseCase
import run.ostrom.server.runners.presentation.runnersRoutes
import run.ostrom.server.runners.presentation.terminalRoutes
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.sessions.CloudAgentSessions
import run.ostrom.server.kernel.sessions.CloudSessionWakes
import run.ostrom.server.kernel.sessions.PendingWaitAnswers
import run.ostrom.server.kernel.sessions.SessionEndListener
import run.ostrom.server.runners.infrastructure.RunnersCloudAgentSessions
import run.ostrom.server.runners.infrastructure.RunnersSessionNames
import run.ostrom.server.runners.infrastructure.RunnersCloudSessionWakes
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Duration.Companion.minutes

private val REPLICA_HTTP = named("runners.replica.http")

/**
 * The runners module: cloud sessions. A member creates a session; the leased reconciler starts one pod per
 * execution (Kubernetes namespace `ostrom`, a local Docker container, or a container on one of the organization's
 * registered runners, `ostrom cloud machine`), with a workspace volume that survives
 * pauses; the runner in the pod reports through the runner channel; the free plan's guards pause idle, overlong or
 * over-budget sessions. Its cloud sessions are agent sessions to the other modules (the kernel's
 * [CloudAgentSessions]): a cloud agent's todo, waits and proposals are its cloud session's. Their names title the
 * notifications about them ([SessionNames]). An answer to a wait of a paused one resumes it ([CloudSessionWakes]), on
 * its owner's behalf and within the plan.
 */
object RunnersModule : ServerModule {
    override val name: String = "runners"

    override val koinModule: Module = module {
        // The console's clusters run the Kubernetes sessions where the cloud module is (always on a full server).
        single<RunnersConfig> { RunnersConfig.from(get<ConfigSource>(), getOrNull<CloudClusterAccess>() != null) }
        single<SessionProfiles> {
            // Kubernetes is chosen only where the cloud module is (RunnersConfig).
            if (get<RunnersConfig>().driver == RunnersConfig.DriverKind.KUBERNETES) {
                // The sessions of the former variables, given their cluster's profile at their next step.
                val legacy = legacyImport(get(), get(), get(), get(), get())
                CatalogSessionProfiles(CloudProfileCatalogImpl(get(), get()), get(), legacy)
            } else {
                NoSessionProfiles
            }
        }
        single<BackfillSessionProfilesUseCase> { BackfillSessionProfilesUseCaseImpl(get(), get(), get()) }
        single(named(CLOUD_ACCESS_SETTINGS.name)) { CLOUD_ACCESS_SETTINGS }
        // The execution's inference credentials need the launches module's agent tokens: without it, none is served.
        single {
            val store = getOrNull<RunnerInferenceTokens>()?.let { tokens ->
                CloudInferenceCredentialStoreImpl(get(), tokens, get(), get())
            }
            InferenceWiring(store)
        }
        single { CloudSessionsRepositoryImpl(get(), get<InferenceWiring>().written) }
        single<CloudSessionsRepository> { get<CloudSessionsRepositoryImpl>() }
        single<CloudAgentSessions> { RunnersCloudAgentSessions(get(), getOrNull<MemberLogins>()) }
        // The notification router titles a notification with its cloud session's name.
        single<SessionNames> { RunnersSessionNames(get()) }
        single<RunnerTokens> { RunnerTokensImpl() }
        // Billing's plans and hours counter when the billing module is there (always on a full server); the free
        // plan and the sessions' own monthly counter otherwise.
        single<CloudPlanProvider> {
            val limits = getOrNull<GetPlanLimitsUseCase>()
            val plans = if (limits == null) FreeCloudPlanProvider() else BillingCloudPlanProvider(limits)
            AllowlistedCloudPlanProvider(SettingsAllowedOrgs(get()), plans)
        }
        single<CloudUsageMeter> {
            val read = getOrNull<GetAgentUsageUseCase>()
            if (read == null) RepositoryCloudUsageMeter(get()) else BillingCloudUsageMeter(read, get())
        }
        single<RunnerAssignmentsRepository> { RunnerAssignmentsRepositoryImpl(get()) }
        single<ExecutionPlanner> {
            // The registered runner's own engine chooses the network; the server only says where it is reached.
            DockerExecutionPlan(null, get<RunnersConfig>().serverUrl, get())
        }
        single<RunnerWorkSignals> { InProcessRunnerWorkSignals(get(), get()) }
        // The console's cloud runtime names the runner image (cloud module); OSTROM_RUNNERS_IMAGE while it names none.
        // A Docker or registered session runs the runtime's session image, a Kubernetes one its profile's image.
        // Its server URL, else OSTROM_RUNNERS_SERVER_URL (an upgraded install's in-cluster URL), else the public one.
        single<RunnerDeliverySource> {
            val config = get<RunnersConfig>()
            val fallbackUrl = config.serverUrl.ifEmpty { get<ConfigSource>()["OSTROM_PUBLIC_URL"].orEmpty() }
            CloudRuntimeRunnerDelivery(getOrNull<CloudRuntimeSource>(), config.runnerImage, fallbackUrl)
        }
        single<RuntimeDriver> {
            RuntimeDrivers.create(get(), { RegisteredRuntimeDriver(get(), get(), get()) }, get()) {
                CloudKubernetesClusters(get())
            }
        }
        single<CreateCloudSessionUseCase> {
            MeteredCreateCloudSession(CreateCloudSessionUseCaseImpl(get(), get(), get(), get(), get()), get())
        }
        single<GetCloudSessionUseCase> { GetCloudSessionUseCaseImpl(get(), get()) }
        single<ListCloudSessionsUseCase> { ListCloudSessionsUseCaseImpl(get()) }
        single<ChangeCloudSessionUseCase> { ChangeCloudSessionUseCaseImpl(get(), get(), get(), get()) }
        single<WakeCloudSessionUseCase> {
            WakeCloudSessionUseCaseImpl(get(), get(), get(), getOrNull<MemberDirectory>(), get(), get())
        }
        // An answer to a wait of a paused cloud session resumes it (automations calls it once the answer is stored).
        single<CloudSessionWakes> { RunnersCloudSessionWakes(get(), get()) }
        // A runner's token, or its pod's projected token where OSTROM_RUNNERS_KUBERNETES_POD_IDENTITY lets the
        // clusters review it.
        single<RunnerCredentials> {
            RunnerCredentialsImpl(get(), get(), get<RunnersConfig>().podTokenReviews(get()), get(), get<Logger>())
        }
        single<CheckRunnerTokenUseCase> { CheckRunnerTokenUseCaseImpl(get()) }
        single<RunnerHeartbeatUseCase> { RunnerHeartbeatUseCaseImpl(get(), get(), get(), get()) }
        single<RecordCloudSessionActivityUseCase> { RecordCloudSessionActivityUseCaseImpl(get(), get()) }
        // The console's paused retention, for every runtime; paused sessions are kept without the cloud module.
        single<PausedRetention> {
            getOrNull<CloudRuntimeSource>()
                ?.let { CloudRuntimePausedRetention(it, get<Logger>()) } ?: KeepPausedSessions
        }
        single<ReconcileCloudSessionUseCase> {
            // The organization's cloud settings when the cloud module is there (always on a full server).
            val limit = CloudSettingsBackgroundWorkLimit(getOrNull<CloudSettingsSource>())
            val ended = getAll<SessionEndListener>()
            val answers = getOrNull<PendingWaitAnswers>() ?: NoPendingWaitAnswers
            val retention = get<PausedRetention>()
            val transfers = get<TransferWiring>().complete
            ReconcileCloudSessionUseCaseImpl(
                get(), get(), get(), get(), get(), get(), ended, get(), answers, transfers, retention, limit,
            )
        }
        // Ownership transfers need the orgs module's members: without it nobody can be named, and none is served.
        single {
            val members = getOrNull<MemberDirectory>()
            val logins = getOrNull<MemberLogins>()
            if (members == null || logins == null) {
                TransferWiring(null, null)
            } else {
                val transfers = CloudTransfersRepositoryImpl(get(), get())
                val recipients = MemberTransferRecipients(members, logins)
                val policy = RuntimeMemberTransferPolicy(getOrNull<CloudRuntimeSource>())
                TransferWiring(
                    CloudTransfersRoutesDependencies(
                        CloudTransfersUseCaseImpl(get(), transfers, recipients, policy, get(), get()),
                        get(),
                    ),
                    CompleteCloudTransferUseCase(
                        get(),
                        transfers,
                        get(),
                        recipients,
                        get(),
                        get(),
                        get<PausedRetention>(),
                    ),
                )
            }
        }
        single { ReconcilerWorker(get(), get(), get(), get()) }
        // The members' GitHub access when the GitHub integration is there (always on a full server).
        single<SetCloudSessionGitHubAccessUseCase> { SetCloudSessionGitHubAccessUseCaseImpl(get(), get(), get()) }
        single<RunnerGitHubTokenUseCase> {
            RunnerGitHubTokenUseCaseImpl(get(), getOrNull<RunnerGitHubTokens>() ?: NoRunnerGitHubTokens)
        }
        single<RunnerVariables> { runnerVariables(getOrNull<ResolveEnvironmentUseCase>()) }
        single<RunnerVariablesUseCase> {
            RunnerVariablesUseCaseImpl(get(), get(), getOrNull<WaitSecretSource>(), getOrNull<MemberDirectory>())
        }
        // The agent of a cloud session, for the secret fields of its waits (waits.secrets.v1).
        single<WaitSecretSessions> { CloudSessionWaitSecretSessions(get()) }
        // Without the orgs module nobody vouches for an owner: the preparation routes are not served (404), so a
        // runner falls back to the legacy clone instead of failing every session.
        single {
            val environment = getOrNull<MemberDirectory>()?.let { members ->
                RunnerEnvironmentUseCaseImpl(
                    get(),
                    get(),
                    get(),
                    getOrNull<RunnerGitHubTokens>() ?: NoRunnerGitHubTokens,
                    members,
                    getOrNull<MemberLogins>() ?: NoMemberLogins,
                    get(),
                )
            }
            val inference = get<InferenceWiring>().store?.let { RunnerInferenceUseCaseImpl(get(), it) }
            CloudSessionsRoutesDependencies(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), environment, inference,
            )
        }
        single<ListRunnersUseCase> { ListRunnersUseCaseImpl(get(), get()) }
        single<RegisteredRunnersRepository> { RegisteredRunnersRepositoryImpl(get()) }
        single<ListRegisteredRunnersUseCase> { ListRegisteredRunnersUseCaseImpl(get(), get()) }
        single<RegisterRunnerUseCase> { RegisterRunnerUseCaseImpl(get(), get(), get()) }
        single<DeleteRunnerUseCase> { DeleteRunnerUseCaseImpl(get()) }
        single<RegisteredRunnerHeartbeatUseCase> {
            RegisteredRunnerHeartbeatUseCaseImpl(get(), get(), get(), get(), get(), get(), get())
        }
        single {
            val config = get<RunnersConfig>()
            val runtime = RunnerRuntime(config.runtimeLabel, config.runnerImage)
            RunnersRoutesDependencies(get(), runtime, get(), get(), get(), get(), get(), get())
        }
        single { TerminalRelay() }
        single<TerminalTickets> { TerminalTicketsImpl(get(), get()) }
        // Who a runner call's credential names; the remote sessions add a device owner's resolver to this list.
        single<List<RunnerPrincipalResolver>> { listOf(RunnerTokenPrincipalResolver(get())) }
        single<AuthenticateRunnerUseCase> { AuthenticateRunnerUseCaseImpl(get(), get()) }
        single<OpenTerminalUseCase> { OpenTerminalUseCaseImpl(get()) }
        single<IssueTerminalTicketUseCase> { IssueTerminalTicketUseCaseImpl(get(), get()) }
        single { TerminalActivity(get(), get()) }
        // Hops to the replica holding a runner's socket, only once replicas are told their URL (one replica: none).
        single(REPLICA_HTTP) { replicaHttpClient() } onClose { it?.close() }
        single {
            val replica = get<RunnersConfig>().replicaUrl?.let { url ->
                TerminalReplica(url, TerminalHostsImpl(get(), url), KtorTerminalForwarder(get(REPLICA_HTTP)))
            }
            TerminalRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), replica)
        }
        // A profile's workspace storage class, read on its cluster where sessions run on Kubernetes (cloud module).
        single<WorkspaceStorageClasses> { ClusterWorkspaceStorageClasses(get<RuntimeDriver>() as? CloudClusterObjects) }
        single<AutomationLauncher> { CloudSessionAutomationLauncher(get(), get(), get()) }
        // Conversation sessions: their transcript, inputs and options, announced.
        single { ConversationAnnouncement() }
        single<CloudConversationStore> { CloudConversationStoreImpl(get(), get()) }
        single<CloudConversationService> { CloudConversationServiceImpl(get(), get(), get(), get(), get()) }
        single { CloudConversationRoutesDependencies(get(), get()) }
        single { CloudConversationMaintenance(get(), get(), getOrNull<CloudRuntimeSource>(), get()) }
        // The runner's side of the conversation: its agent, transcript upload and inputs.
        single<RunnerConversationStore> { RunnerConversationStoreImpl(get(), get()) }
        single<RunnerConversationUseCase> {
            RunnerConversationUseCaseImpl(get(), get(), get(), getOrNull<MemberLogins>() ?: NoMemberLogins, get())
        }
        single { RunnerConversationRoutesDependencies(get(), get()) }
        single<AutomationLimits> { CloudSettingsAutomationLimits(getOrNull<CloudSettingsSource>()) }
    }

    override val migrations: List<SchemaMigration> = RunnersMigrations

    /**
     * The cluster of the former `OSTROM_RUNNERS_KUBERNETES_*` variables, imported once, after the cloud runtime; then
     * every session created before cloud profiles is given one on it now, not only at its next step.
     */
    override suspend fun initialize(koin: Koin) {
        val clusters = koin.getOrNull<CloudClustersUseCase>() ?: return
        if (koin.get<RunnersConfig>().driver != KUBERNETES) return
        val logger = koin.get<Logger>()
        legacyImport(clusters, koin.get(), koin.get(), logger, koin.get()).start()
        val given = koin.get<BackfillSessionProfilesUseCase>()()
        if (given > 0) {
            logger.info("Gave the sessions of the former variables their cloud profile.", mapOf("sessions" to given))
        }
    }

    private fun legacyImport(
        clusters: CloudClustersUseCase,
        runtime: CloudRuntimeSource,
        config: RunnersConfig,
        logger: Logger,
        source: ConfigSource,
    ) = LegacyKubernetesImport(clusters, runtime, config.runnerImage, logger, source::get)

    override fun routes(route: Route, koin: Koin) {
        route.cloudSessionsRoutes(koin.get())
        koin.get<TransferWiring>().routes?.let { route.cloudTransfersRoutes(it) }
        route.runnersRoutes(koin.get())
        route.terminalRoutes(koin.get())
        route.cloudConversationRoutes(koin.get())
        route.runnerConversationRoutes(koin.get())
    }

    /**
     * Registered runners; cloud runners only with a runtime (the console's clusters, Docker or registered runners),
     * their agent token bound to their cloud session ([CloudAgentSessions], bound with them); their GitHub access once
     * members can link a GitHub account; their profiles on the console's clusters.
     */
    override suspend fun features(koin: Koin): Set<String> {
        if (koin.get<RunnersConfig>().driver == RunnersConfig.DriverKind.NONE) return setOf(ServerFeatures.RUNNERS)
        // The conversation keys, files aside (not served yet), with a runtime driver.
        val announced = koin.getOrNull<ConversationAnnouncement>()?.announced == true
        val conversation = if (announced) CONVERSATION_FEATURES else emptySet()
        val github = koin.getOrNull<RunnerGitHubTokens>()?.available() == true
        // The members' values, once the secrets module resolves them.
        val variables = koin.getOrNull<ResolveEnvironmentUseCase>() != null
        val members = koin.getOrNull<MemberDirectory>() != null
        return setOfNotNull(
            ServerFeatures.RUNNERS,
            ServerFeatures.RUNNERS_CLOUD,
            ServerFeatures.RUNNERS_CLOUD_AGENT,
            // A creation's Idempotency-Key, on every cloud runtime.
            ServerFeatures.RUNNERS_CLOUD_IDEMPOTENCY,
            // The recipe, the Git identity and the owner's values (none without the secrets module), once the orgs
            // module says who is an active member: without it, no session would prepare.
            ServerFeatures.RUNNERS_CLOUD_ENVIRONMENT.takeIf { members },
            ServerFeatures.RUNNERS_CLOUD_GITHUB.takeIf { github },
            ServerFeatures.RUNNERS_CLOUD_VARIABLES.takeIf { variables },
            // The execution's inference credential, once the launches module opens the executions' launches.
            ServerFeatures.RUNNERS_CLOUD_INFERENCE.takeIf { koin.getOrNull<InferenceWiring>()?.store != null },
            // Giving a session to another member, once the orgs module names the members.
            ServerFeatures.RUNNERS_CLOUD_TRANSFER.takeIf { koin.getOrNull<TransferWiring>()?.routes != null },
            // Sessions on the console's clusters and the organizations' profiles.
            ServerFeatures.RUNNERS_CLOUD_PROFILES.takeIf { koin.get<RunnersConfig>().driver == KUBERNETES },
            // Proven workspace deletion, the storage class check and the garbage collection are Kubernetes's; the
            // expiry of paused sessions holds on every runtime.
            ServerFeatures.RUNNERS_CLOUD_RETENTION.takeIf { koin.get<RunnersConfig>().driver == KUBERNETES },
        ) + conversation
    }

    /** What conversation sessions announce, once [ConversationAnnouncement.announced]: every key but files. */
    val CONVERSATION_FEATURES: Set<String> = setOf(
        ServerFeatures.RUNNERS_CLOUD_CONVERSATION,
        ServerFeatures.RUNNERS_CLOUD_PERMISSIONS,
        ServerFeatures.RUNNERS_CLOUD_PERMISSIONS_BYPASS,
        ServerFeatures.RUNNERS_CLOUD_TASKS,
        ServerFeatures.RUNNERS_CLOUD_TRANSCRIPT_LIVE,
        ServerFeatures.RUNNERS_CLOUD_TRANSCRIPT_FEEDS,
        ServerFeatures.RUNNERS_CLOUD_MODEL,
        ServerFeatures.RUNNERS_CLOUD_EFFORT,
    )

    override fun start(scope: CoroutineScope, koin: Koin) {
        val worker = koin.get<ReconcilerWorker>()
        // Undispatched: the loop is running, up to its first query, when the server starts serving.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { worker.run() }
        val signals = koin.get<RunnerWorkSignals>()
        scope.launch { signals.watch() }
        val maintenance = koin.get<CloudConversationMaintenance>()
        scope.launch { maintenance.repeat(koin.scheduledWork(), koin.get()) }
        // Every minute, a bounded batch of the creations' expired idempotency records is deleted (TeamCode's
        // maintain), on one replica at a time; a failed purge is logged and tried again the next minute.
        val sessions = koin.get<CloudSessionsRepository>()
        val work = koin.scheduledWork()
        scope.launch {
            work.repeatDue(CREATE_KEYS_PURGE, koin.get<Logger>(), "cloud create keys purge failed") {
                sessions.purgeExpiredCreateKeys()
            }
        }
        // Every minute, a bounded batch of the inference credentials expired or left by an execution that no longer
        // runs is revoked and deleted (TeamCode's maintenance pass), on one replica at a time.
        koin.get<InferenceWiring>().store?.let { credentials ->
            scope.launch {
                work.repeatDue(INFERENCE_PURGE, koin.get<Logger>(), "cloud inference credentials purge failed") {
                    credentials.purge(INFERENCE_PURGE_LIMIT)
                }
            }
        }
        garbageWorker(koin)?.let { garbage -> scope.launch { garbage.run() } }
    }

    /** The clusters' garbage collection, where sessions run on the console's clusters and periodic jobs are leased. */
    private fun garbageWorker(koin: Koin): CloudGarbageWorker? {
        // The console's clusters, and so the cloud module, are there whenever the runtime is Kubernetes.
        val objects = koin.get<RuntimeDriver>() as? CloudClusterObjects ?: return null
        val clusters = CloudGarbageClusters(koin.get<CloudClustersRepository>())
        val live = LiveCloudObjectsRepositoryImpl(koin.get())
        val collect = CollectCloudGarbageUseCaseImpl(live, objects, clusters, koin.get())
        return CloudGarbageWorker(koin.scheduledWork(), clusters, collect, koin.get())
    }

    /** The purge of the expired idempotency records of the creations. */
    private val CREATE_KEYS_PURGE = ScheduledJob("runners/create-keys", 1.minutes, 1.minutes)

    /** The purge of the stale inference credentials. */
    private val INFERENCE_PURGE = ScheduledJob("runners/inference-credentials", 1.minutes, 1.minutes)

    /** At most this many credentials a pass, as the creations' keys. */
    private const val INFERENCE_PURGE_LIMIT = 500
}

/**
 * What the ownership transfers need, built once: their routes and the reconciler's side of them, both null without the
 * orgs module.
 *
 * @property routes The routes' dependencies.
 * @property complete The owner change at the paused boundary.
 */
class TransferWiring(val routes: CloudTransfersRoutesDependencies?, val complete: CompleteCloudTransferUseCase?)

/**
 * The execution's inference credentials, built once (`runners.cloud.inference.v1`): null without the launches module,
 * whose agent tokens they are.
 *
 * @property store The credentials.
 */
class InferenceWiring(val store: CloudInferenceCredentialStore?) {
    /** What a session write tells: the store's revocation of an ended execution's credentials, or nobody. */
    val written: CloudSessionWritten = store?.let { credentials ->
        CloudSessionWritten { session -> credentials.revokeEnded(session) }
    } ?: NoCloudSessionWritten
}
