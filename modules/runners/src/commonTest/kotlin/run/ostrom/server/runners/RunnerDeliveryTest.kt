// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.infrastructure.CloudRuntimeRunnerDelivery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerDeliveryTest {
    private fun source(runtime: CloudRuntime) = object : CloudRuntimeSource {
        override suspend fun current(): CloudRuntime = runtime
    }

    @Test
    fun theConsoleRuntimeNamesTheRunnerImageItsBinaryAndTheSessionImage() = runTest {
        val image = "docker.io/ostromhq/ostrom-runner:1@sha256:ab"
        val runtime = CloudRuntime(runnerImage = image, runnerBinary = "/bin/ostrom", sessionImage = "session:1")
        // The runner image only delivers the binary: a Docker or registered session runs the runtime's session image.
        assertEquals(
            RunnerDelivery(image, "/bin/ostrom", "session:1"),
            CloudRuntimeRunnerDelivery(source(runtime), "fallback:1").current(),
        )
    }

    @Test
    fun withoutARunnerImageTheConfiguredOneIsUsedAndTheSessionImageDefaults() = runTest {
        assertEquals(
            RunnerDelivery("fallback:1", RunnerDelivery.DEFAULT_RUNNER_BINARY, CloudRuntime.DEFAULT_SESSION_IMAGE),
            CloudRuntimeRunnerDelivery(source(CloudRuntime()), "fallback:1").current(),
        )
        assertEquals(
            "fallback:1",
            CloudRuntimeRunnerDelivery(source(CloudRuntime(runnerImage = " ")), "fallback:1").current().runnerImage,
        )
        val blank = CloudRuntimeRunnerDelivery(source(CloudRuntime(sessionImage = " ")), "fallback:1").current()
        assertEquals(CloudRuntime.DEFAULT_SESSION_IMAGE, blank.sessionImage)
        val noCloud = CloudRuntimeRunnerDelivery(null, "fallback:1").current()
        assertEquals("fallback:1" to CloudRuntime.DEFAULT_SESSION_IMAGE, noCloud.runnerImage to noCloud.sessionImage)
    }

    @Test
    fun theRunnerCallsTheRuntimesServerUrlElseThePublicOne() = runTest {
        val public = "https://api.acme.dev/"
        val stored = CloudRuntime(serverUrl = "https://runners.acme.dev")
        val fromRuntime = CloudRuntimeRunnerDelivery(source(stored), "f", public).current().serverUrl
        assertEquals("https://runners.acme.dev", fromRuntime)
        val unset = CloudRuntime()
        assertEquals("https://api.acme.dev", CloudRuntimeRunnerDelivery(source(unset), "f", public).current().serverUrl)
        val blank = CloudRuntime(serverUrl = " ")
        assertEquals("https://api.acme.dev", CloudRuntimeRunnerDelivery(source(blank), "f", public).current().serverUrl)
    }
}
