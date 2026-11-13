// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.identity.presentation.SignInConfig
import run.ostrom.server.kernel.auth.SignOutListener
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.notifications.domain.ApprovalNotices
import run.ostrom.server.notifications.domain.ApproverNotificationsChannel
import run.ostrom.server.notifications.domain.Approvers
import run.ostrom.server.notifications.domain.DeletePushDeviceUseCase
import run.ostrom.server.notifications.domain.DeletePushDeviceUseCaseImpl
import run.ostrom.server.notifications.domain.LiveCredentials
import run.ostrom.server.notifications.domain.NotificationOutbox
import run.ostrom.server.notifications.domain.PersonOrganizations
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.domain.PushDevicesSignOutListener
import run.ostrom.server.notifications.domain.PushSender
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCase
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCaseImpl
import run.ostrom.server.notifications.infrastructure.ExpoPushSender
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The notifications module: the phones a person registers for push notifications, one per push token in each
 * organization, and what tells an approver a gate waits for their yes (a push to those phones and an e-mail), sent
 * apart from the request by [NotificationOutbox]. A phone is forgotten when its organization is deleted or its person
 * signs out with the credential that registered it.
 */
object NotificationsModule : ServerModule {
    // Declared before koinModule: the object initializes in order, and the module lambda runs at once.
    private val HTTP = named("notifications-http")
    private const val HTTP_TIMEOUT_MS = 10_000L

    override val name: String = "notifications"

    override val koinModule: Module = module {
        single { NotificationSettings.from(get()) }
        single<PushDevicesRepository> { PushDevicesRepositoryImpl(get()) }
        single<RegisterPushDeviceUseCase> { RegisterPushDeviceUseCaseImpl(get(), get()) }
        single<DeletePushDeviceUseCase> { DeletePushDeviceUseCaseImpl(get()) }
        single { PushDevicesRoutesDependencies(get(), get(), get()) }
        // A deleted organization's phones are forgotten at once (OrgsModule calls every bound listener).
        single<OrgDeletionListener>(named("notifications")) { PushDevicesOrgDeletionListener(get()) }
        single<PersonOrganizations> { MemberOrganizations(get()) }
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
        single<PushSender> {
            val settings = get<NotificationSettings>()
            ExpoPushSender(get(HTTP), settings.expoPushUrl, settings.expoAccessToken)
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
                mailing = get<SignInConfig>().sendsMail(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = NotificationsMigrations

    override fun routes(route: Route, koin: Koin) {
        route.pushDevicesRoutes(koin.get())
    }

    /**
     * Approval notifications, and push to the phones: the Expo push service needs no configuration (its URL has a
     * default, `OSTROM_EXPO_ACCESS_TOKEN` only for a project that requires one), so a push sender is always there.
     */
    override suspend fun features(koin: Koin): Set<String> =
        setOf(ServerFeatures.NOTIFICATIONS, ServerFeatures.NOTIFICATIONS_PUSH)

    /** Sends the queued notifications until the server stops. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val outbox = koin.get<NotificationOutbox>()
        val settings = koin.get<NotificationSettings>()
        val mailing = koin.get<SignInConfig>().sendsMail()
        koin.get<Logger>().log(
            settings.startLevel(mailing),
            "approval notifications started; e-mail links open OSTROM_WEB_ORIGIN",
            mapOf("settings" to settings.toString(), "emails" to mailing),
        )
        scope.launch { outbox.run() }
    }
}

/**
 * Whether the instance sends e-mails at all: the identity module's provider is Resend or a directory. Without one, no
 * e-mail is queued (it could only fail).
 *
 * @return True with `OSTROM_RESEND_API_KEY` or `OSTROM_MAIL_DIR`.
 */
internal fun SignInConfig.sendsMail(): Boolean = resendApiKey != null || mailDirectory != null
