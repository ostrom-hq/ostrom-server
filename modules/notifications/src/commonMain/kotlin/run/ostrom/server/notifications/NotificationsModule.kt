// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.kernel.auth.SignOutListener
import run.ostrom.server.kernel.database.ScheduledJob
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.database.repeatDue
import run.ostrom.server.kernel.database.scheduledWork
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.WaitSummaries
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.ApproverNotificationsChannel
import run.ostrom.server.notifications.domain.Approvers
import run.ostrom.server.notifications.domain.DeletePushDeviceUseCase
import run.ostrom.server.notifications.domain.DeliverNotificationsUseCase
import run.ostrom.server.notifications.domain.DeliverNotificationsUseCaseImpl
import run.ostrom.server.notifications.domain.NotificationDeliveriesRepository
import run.ostrom.server.notifications.domain.DeletePushDeviceUseCaseImpl
import run.ostrom.server.notifications.domain.EncryptedPushSender
import run.ostrom.server.notifications.domain.FirebaseSenders
import run.ostrom.server.notifications.domain.PushProviders
import run.ostrom.server.notifications.domain.PushRelayIdentitiesRepository
import run.ostrom.server.notifications.domain.RelaySenders
import run.ostrom.server.notifications.infrastructure.CachedRelaySenders
import run.ostrom.server.notifications.infrastructure.PushRelayIdentitiesRepositoryImpl
import run.ostrom.server.notifications.infrastructure.RelayPushSender
import run.ostrom.server.kernel.network.outboundHttpClient
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.notifications.domain.LiveCredentials
import run.ostrom.server.notifications.domain.NotificationOutbox
import run.ostrom.server.notifications.domain.PUSH_SETTINGS
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.notifications.domain.PUSH_CHANNEL
import run.ostrom.server.notifications.domain.PushChannel
import run.ostrom.server.notifications.domain.SendTestPushUseCase
import run.ostrom.server.notifications.domain.SendTestPushUseCaseImpl
import run.ostrom.server.notifications.presentation.PushTestRoutesDependencies
import run.ostrom.server.notifications.presentation.pushTestRoutes
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import run.ostrom.server.notifications.domain.NotificationSettingsService
import run.ostrom.server.notifications.domain.NotificationSettingsServiceImpl
import run.ostrom.server.notifications.infrastructure.NotificationDeliveriesOrgDeletionListener
import run.ostrom.server.notifications.infrastructure.NotificationDeliveriesRepositoryImpl
import run.ostrom.server.notifications.infrastructure.NotificationSettingsOrgDeletionListener
import run.ostrom.server.notifications.infrastructure.NotificationSettingsRepositoryImpl
import run.ostrom.server.notifications.presentation.NotificationSettingsRoutesDependencies
import run.ostrom.server.notifications.presentation.notificationSettingsRoutes
import run.ostrom.server.notifications.domain.PersonOrganizations
import run.ostrom.server.notifications.domain.PushEncryption
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.domain.PushDevicesSignOutListener
import run.ostrom.server.notifications.domain.PushSender
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCase
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCaseImpl
import run.ostrom.server.notifications.infrastructure.CachedFirebaseSenders
import run.ostrom.server.notifications.infrastructure.EciesPushEncryption
import run.ostrom.server.notifications.infrastructure.FirebasePushSender
import run.ostrom.server.notifications.infrastructure.IdentityLiveCredentials
import run.ostrom.server.notifications.infrastructure.MemberApprovers
import run.ostrom.server.notifications.infrastructure.MemberOrganizations
import run.ostrom.server.notifications.infrastructure.NotificationSettings
import run.ostrom.server.notifications.infrastructure.NotificationsMigrations
import run.ostrom.server.notifications.infrastructure.PushDevicesOrgDeletionListener
import run.ostrom.server.notifications.infrastructure.PushDevicesRepositoryImpl
import run.ostrom.server.notifications.infrastructure.startLevel
import run.ostrom.server.notifications.presentation.PushDevicesRoutesDependencies
import run.ostrom.server.notifications.presentation.pushDevicesRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The notifications module: the phones a person registers for push notifications, one per push token in each
 * organization, and what tells an approver a gate waits for their yes (a push to those phones and an e-mail), sent
 * apart from the request by [NotificationOutbox]. A phone is forgotten when its organization is deleted or its person
 * signs out with the credential that registered it. Each member's switches of the notification router
 * (`/api/v1/notifications/settings`, TeamCode's `notifications.v1`) are kept here, per channel and kind.
 */
object NotificationsModule : ServerModule {
    // Declared before koinModule: the object initializes in order, and the module lambda runs at once.
    private val HTTP = named("notifications-http")
    private val RELAY_HTTP = named("notifications-relay-http")
    private const val HTTP_TIMEOUT_MS = 10_000L

    /** The router's pass, every second, one replica at a time (TeamCode's `notifications/deliver`). */
    private val DELIVER = ScheduledJob("notifications/deliver", 1.seconds, 45.seconds)

    /** The queue's upkeep, each minute (TeamCode's `notifications/maintain`). */
    private val MAINTAIN = ScheduledJob("notifications/maintain", 1.minutes, 30.seconds)

    /** No wait is known: a server built without the automations module. */
    private val NO_WAITS = WaitSummaries { _, _ -> emptyList() }

    override val name: String = "notifications"

    override val koinModule: Module = module {
        single { NotificationSettings.from(get()) }
        single<PushDevicesRepository> { PushDevicesRepositoryImpl(get()) }
        single<PushEncryption> { EciesPushEncryption() }
        single<RegisterPushDeviceUseCase> { RegisterPushDeviceUseCaseImpl(get(), get(), get()) }
        // Admin → Settings → push: the provider, the relay URL and the Firebase service account, sealed (admin module).
        single(named("notifications-push")) { PUSH_SETTINGS }
        single<DeletePushDeviceUseCase> { DeletePushDeviceUseCaseImpl(get()) }
        single { PushDevicesRoutesDependencies(get(), get(), get()) }
        // A deleted organization's phones are forgotten at once (OrgsModule calls every bound listener).
        single<OrgDeletionListener>(named("notifications")) { PushDevicesOrgDeletionListener(get()) }
        single<PersonOrganizations> { MemberOrganizations(get()) }
        router()
        // The identity module tells every bound listener after a sign-out.
        single<SignOutListener>(named("notifications")) { PushDevicesSignOutListener(get(), get(), get()) }
        single(HTTP) {
            HttpClient {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = HTTP_TIMEOUT_MS
                    requestTimeoutMillis = HTTP_TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        single<FirebaseSenders> {
            val http = get<HttpClient>(HTTP)
            val clock = get<Clock>()
            CachedFirebaseSenders { account -> FirebasePushSender(account, http, clock) }
        }
        // The relay URL is an administrator's: the kernel's outbound client checks every address it connects to. A
        // relay of one's own on one's network passes; link-local and cloud metadata addresses never do (TeamCode).
        single(RELAY_HTTP) {
            outboundHttpClient(allowPrivate = true) {
                install(HttpTimeout) {
                    connectTimeoutMillis = HTTP_TIMEOUT_MS
                    requestTimeoutMillis = HTTP_TIMEOUT_MS
                }
            }
        } onClose { it?.close() }
        single<PushRelayIdentitiesRepository> { PushRelayIdentitiesRepositoryImpl(get(), get()) }
        single<RelaySenders> {
            // Resolved at the first push through a relay: the secrets key and the client only then.
            val scope = this
            CachedRelaySenders { url ->
                RelayPushSender(url, scope.get(), scope.get<SecretCipher>(), scope.get(RELAY_HTTP), scope.get())
            }
        }
        single { PushProviders(get(), get(), get()) }
        single<PushSender> {
            val settings = get<NotificationSettings>()
            EncryptedPushSender(get(), get(), get(), settings.publicUrl, get())
        }
        single { NotificationOutbox(get(), get(), get()) }
        single<Approvers> { MemberApprovers(get(), get(), get()) }
        single<LiveCredentials> { IdentityLiveCredentials(get(), get(), get()) }
        // The gates module tells every bound channel of a request (ChannelsGateNotifier).
        single<GateChannel>(named("notifications")) {
            ApproverNotificationsChannel(
                approvers = get(),
                devices = get(),
                credentials = get(),
                notices = ApprovalNotices(get<NotificationSettings>().webOrigin),
                outbox = get(),
                mailing = get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = NotificationsMigrations

    /**
     * The notification router's half this module owns (docs/notifications.md): the members' switches and their
     * routes, and the channels the server offers, the [NotificationChannel]s bound in Koin.
     */
    private fun Module.router() {
        single<NotificationSettingsRepository> { NotificationSettingsRepositoryImpl(get()) }
        single<NotificationSettingsService> {
            val scope = this
            NotificationSettingsServiceImpl(get()) {
                scope.getAll<NotificationChannel>().map { it.id }.distinct().sorted()
            }
        }
        single { NotificationSettingsRoutesDependencies(get(), get()) }
        single {
            PushChannel(get(), get(), get(), get(), get<NotificationSettings>().publicUrl, get())
        }
        single<NotificationChannel>(named(PUSH_CHANNEL)) { get<PushChannel>() }
        single<SendTestPushUseCase> {
            val scope = this
            SendTestPushUseCaseImpl({ scope.get<PushChannel>() }, get())
        }
        single { PushTestRoutesDependencies(get(), get()) }
        single<OrgDeletionListener>(named("notifications-settings")) { NotificationSettingsOrgDeletionListener(get()) }
        // The delivery queue, fed from the event journal (the events module binds it).
        single<NotificationDeliveriesRepository> {
            val scope = this
            NotificationDeliveriesRepositoryImpl(get()) { scope.get<OrgEventJournal>() }
        }
        single<DeliverNotificationsUseCase> {
            val scope = this
            DeliverNotificationsUseCaseImpl(
                journal = get(),
                waits = getOrNull() ?: NO_WAITS,
                settings = get(),
                deliveries = get(),
                channels = { scope.getAll<NotificationChannel>().distinctBy { it.id } },
                clock = get(),
                logger = get(),
            )
        }
        single<OrgDeletionListener>(named("notifications-deliveries")) {
            NotificationDeliveriesOrgDeletionListener(get())
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.pushDevicesRoutes(koin.get())
        route.notificationSettingsRoutes(koin.get())
        route.pushTestRoutes(koin.get())
    }

    /**
     * Approval notifications, and push to the phones: a phone registers its FCM token and public key whatever the
     * provider, and its pushes go out once an administrator chooses one (Admin → Settings → push). Any member signed in
     * interactively may send a test to their phones.
     */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(ServerFeatures.NOTIFICATIONS, ServerFeatures.NOTIFICATIONS_PUSH, ServerFeatures.NOTIFICATIONS_PUSH_TEST)

    /** Sends the queued approval notifications and runs the router's worker until the server stops. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val outbox = koin.get<NotificationOutbox>()
        val settings = koin.get<NotificationSettings>()
        val mailing = koin.get<MailAvailability>()
        val logger = koin.get<Logger>()
        settings.warnDeprecated(logger)
        scope.launch {
            // Whether e-mails go now: an administrator may set the mail provider in the console later. A failed read
            // (the database at start) only makes the line quieter.
            val emails = try {
                mailing.available()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            logger.log(
                settings.startLevel(emails),
                "approval notifications started; e-mail links open OSTROM_CONSOLE_URL",
                mapOf("settings" to settings.toString(), "emails" to emails),
            )
        }
        scope.launch { outbox.run() }
        // The router runs where the event journal is: a server built without the events module has nothing to route.
        if (koin.getOrNull<OrgEventJournal>() != null) {
            val deliver = koin.get<DeliverNotificationsUseCase>()
            val work = koin.scheduledWork()
            scope.launch { work.repeatDue(DELIVER, logger, "notification delivery failed") { deliver() } }
            scope.launch { work.repeatDue(MAINTAIN, logger, "notification maintenance failed") { deliver.maintain() } }
        }
    }
}
