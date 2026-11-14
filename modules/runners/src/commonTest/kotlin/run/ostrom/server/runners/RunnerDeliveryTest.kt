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
    fun theConsoleRuntimeNamesTheRunnerImageAndItsBinary() = runTest {
        val image = "ghcr.io/ostrom-hq/ostrom-runner:1@sha256:ab"
        val runtime = CloudRuntime(runnerImage = image, runnerBinary = "/bin/ostrom")
        assertEquals(
            RunnerDelivery(image, "/bin/ostrom", image),
            CloudRuntimeRunnerDelivery(source(runtime), "fallback:1").current(),
        )
    }

    @Test
    fun withoutARunnerImageTheConfiguredOneIsUsed() = runTest {
        assertEquals(
            RunnerDelivery("fallback:1", RunnerDelivery.DEFAULT_RUNNER_BINARY, "fallback:1"),
            CloudRuntimeRunnerDelivery(source(CloudRuntime()), "fallback:1").current(),
        )
        assertEquals(
            "fallback:1",
            CloudRuntimeRunnerDelivery(source(CloudRuntime(runnerImage = " ")), "fallback:1").current().runnerImage,
        )
        assertEquals("fallback:1", CloudRuntimeRunnerDelivery(null, "fallback:1").current().sessionImage)
    }
}
