// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.server.workflowruns.domain.services.NotificationChannels
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.fakes.RecordingChannels
import run.ostrom.server.workflowruns.infrastructure.LogChannels
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowRunsModuleTest {
    @Test
    fun withoutAnIntegrationTheLogChannelsDeliverAndAnIntegrationKeepsItsOwn() = runTest {
        val bare = koinApplication { }.koin
        WorkflowRunsModule.initialize(bare)
        assertTrue(bare.get<NotificationChannels>() is LogChannels)
        val integration = RecordingChannels()
        val bound = koinApplication { modules(module { single<NotificationChannels> { integration } }) }.koin
        WorkflowRunsModule.initialize(bound)
        assertSame(integration, bound.get<NotificationChannels>())
    }

    @Test
    fun aLogChannelIsSentAndAnyOtherIsNotConnectedHere() = runTest {
        val channels = LogChannels()
        assertEquals(NotificationStatus.SENT, channels.deliver(ORG, "log:team", "t").status)
        val slack = channels.deliver(ORG, "slack:#deploys", "t")
        assertEquals(NotificationStatus.NOT_SENT, slack.status)
        assertEquals("The slack channel is not connected to this instance.", slack.error)
    }
}
