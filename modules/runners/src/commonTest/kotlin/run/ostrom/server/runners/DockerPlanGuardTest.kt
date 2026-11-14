// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.ExecutionPlan
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.PlannedObject
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.DockerExecutionPlan
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Every plan the server hands a registered runner passes the machine's `PlanGuard` (ostrom-cli
 * `runner/src/commonMain/kotlin/run/ostrom/cli/runner/PlanGuard.kt`, ostrom-cli#175), here as [guard]: its rules,
 * with the same patterns, and the delivery's own invariants (the binary volume is a planned volume of the plan, the
 * delivery mounts it alone and first).
 */
class DockerPlanGuardTest {
    private val delivery = RunnerDelivery(RUNNER_IMAGE, RunnerDelivery.DEFAULT_RUNNER_BINARY, SESSION_IMAGE)

    // A registered runner's plan: no network, the machine's engine chooses it.
    private val planner = DockerExecutionPlan(null, "https://ostrom.example.com", RunnerDeliverySource { delivery })

    private fun spec(epoch: Long, agent: AgentKind = AgentKind.CLAUDE) = ExecutionSpec(
        Uuid.random(),
        ORG_A,
        epoch,
        agent,
        RepositorySpec("https://github.com/acme/api.git", "main"),
        "ostrom_runner_xyz",
        CloudPlan.FREE.sessionLimits(),
        model = "opus",
        prompt = "Fix it",
    )

    @Test
    fun everyPlanPassesTheMachinesGuardPinnedToItsImages() = runTest {
        // The first epoch, a later one, and the longest the guard accepts (18 digits).
        for (epoch in listOf(1L, 42L, 999_999_999_999_999_999L)) {
            for (agent in AgentKind.entries) {
                val plan = planner.plan(spec(epoch, agent))
                assertEquals(emptyList(), guard(plan, SESSION_IMAGE, RUNNER_IMAGE), "epoch $epoch, ${agent.wire}")
                // Unpinned, as a machine without OSTROM_RUNNER_SESSION_IMAGE.
                assertEquals(emptyList(), guard(plan, null, null))
            }
        }
    }

    @Test
    fun theDeliveryComesFirstAloneOnItsOwnBinaryWhichThePlanCreates() = runTest {
        val spec = spec(7)
        val plan = planner.plan(spec)
        val binary = "ostrom-ws-${spec.sessionId.toHexString()}-e7-bin"
        assertTrue(binary in plan.volumes.map { it.name }, "The binary volume is a planned volume")
        assertEquals("ostrom-s-${spec.sessionId.toHexString()}-e7-bin", plan.containers.first().name)
        assertEquals(listOf("$binary:/usr/local/bin"), binds(plan.containers.first()))
        for (container in plan.containers.drop(1)) {
            assertEquals(listOf("$binary:/opt/ostrom:ro"), binds(container).filter { it.startsWith(binary) })
        }
    }

    @Test
    fun theGuardHereRefusesWhatTheMachineRefuses() = runTest {
        val plan = planner.plan(spec(3))
        val (delivery, agent) = plan.containers
        // The agent first to mount the binary: it would fill the volume from the session image.
        val swapped = ExecutionPlan(plan.volumes, listOf(agent, delivery) + plan.containers.drop(2))
        assertTrue("The delivery container must be the first to mount the binary." in guard(swapped, null, null))
        // Another image than the pinned ones.
        assertTrue("A container's image is refused." in guard(plan, "other:1", RUNNER_IMAGE))
        assertTrue("A container's image is refused." in guard(plan, SESSION_IMAGE, "other:1"))
        // A session image pinned without the delivery image.
        assertTrue(guard(plan, SESSION_IMAGE, null).any { "OSTROM_RUNNER_DELIVERY_IMAGE" in it })
        // A binary volume the plan does not create.
        val unplanned = ExecutionPlan(plan.volumes.filterNot { it.name.endsWith("-bin") }, plan.containers)
        assertTrue("The binary volume must be a planned volume." in guard(unplanned, null, null))
        // A delivery that mounts its binary read-only, or something more.
        val readOnly = delivery.withBinds(binds(delivery).map { "$it:ro" })
        assertTrue("A delivery container fills its binary volume." in guard(plan.with(readOnly), null, null))
        val more = delivery.withBinds(binds(delivery) + binds(agent).first())
        assertTrue(
            "A delivery container mounts its execution's binary volume only." in guard(plan.with(more), null, null),
        )
        // A session's container that writes the binary.
        val writes = agent.withBinds(binds(agent).map { it.removeSuffix(":ro") })
        val written = ExecutionPlan(plan.volumes, listOf(delivery, writes) + plan.containers.drop(2))
        assertTrue("A session's container mounts the binary read-only." in guard(written, null, null))
    }

    private fun ExecutionPlan.with(first: PlannedObject) = ExecutionPlan(volumes, listOf(first) + containers.drop(1))

    private fun PlannedObject.withBinds(binds: List<String>): PlannedObject {
        val host = body.getValue("HostConfig") as JsonObject
        val replaced = JsonObject(host + ("Binds" to JsonArray(binds.map(::JsonPrimitive))))
        return PlannedObject(name, JsonObject(body + ("HostConfig" to replaced)))
    }

    private fun binds(container: PlannedObject): List<String> =
        texts((container.body["HostConfig"] as JsonObject)["Binds"]).filterNotNull()
}

private const val RUNNER_IMAGE = "docker.io/ostromhq/ostrom-runner:1"
private const val SESSION_IMAGE = "ghcr.io/acme/session:3"

private const val DELIVERY_SUFFIX = "-bin"
private val VOLUME = Regex("ostrom-ws-[0-9a-f]{32}(-socket|-checks|-e[0-9]{1,18}-bin)?")
private val CONTAINER = Regex("ostrom-s-[0-9a-f]{32}-e[0-9]{1,18}(-agent|-checks|-bin)?")
private val BIND = Regex("ostrom-ws-[0-9a-f]{32}(-socket|-checks|-e[0-9]{1,18}-bin)?:/[A-Za-z0-9._/-]+(:ro)?")
private val BINARY_BIND = Regex("ostrom-ws-[0-9a-f]{32}-e[0-9]{1,18}-bin:.*")
private val USER = Regex("[1-9][0-9]*:[1-9][0-9]*")
private val VOLUME_KEYS = setOf("Name", "Labels")
private val CONTAINER_KEYS = setOf("Image", "User", "Entrypoint", "WorkingDir", "Env", "Labels", "HostConfig")
private val HOST_KEYS = setOf(
    "Binds", "GroupAdd", "ExtraHosts", "RestartPolicy", "CapDrop", "SecurityOpt", "ReadonlyRootfs", "Tmpfs",
    "PidsLimit", "Memory", "NanoCpus",
)

/**
 * The machine's `PlanGuard.check`, its refusals as a list (empty when the plan is accepted).
 *
 * @param image The machine's `OSTROM_RUNNER_SESSION_IMAGE`, or null.
 * @param runnerImage The machine's `OSTROM_RUNNER_DELIVERY_IMAGE`, or null.
 */
private fun guard(plan: ExecutionPlan, image: String?, runnerImage: String?): List<String> = buildList {
    fun require(condition: Boolean, refusal: String) {
        if (!condition) add(refusal)
    }
    val first = plan.containers.indexOfFirst { container -> hostBinds(container).any { BINARY_BIND.matches(it) } }
    require(
        first < 0 || plan.containers[first].name.endsWith(DELIVERY_SUFFIX),
        "The delivery container must be the first to mount the binary.",
    )
    for (volume in plan.volumes) {
        require(VOLUME.matches(volume.name), "A volume must be a session's, not ${volume.name}.")
        require(volume.body.keys.all { it in VOLUME_KEYS }, "A volume asks for more than a name and labels.")
        require(text(volume.body["Name"]) == volume.name, "A volume's body must name it.")
    }
    for (container in plan.containers) {
        val body = container.body
        require(CONTAINER.matches(container.name), "A container must be an execution's, not ${container.name}.")
        require(body.keys.all { it in CONTAINER_KEYS }, "A container asks for more than a session's container.")
        val delivery = container.name.endsWith(DELIVERY_SUFFIX)
        require(
            !delivery || image == null || runnerImage != null,
            "A machine that pins the session image must pin the delivery image (OSTROM_RUNNER_DELIVERY_IMAGE).",
        )
        val allowed = if (delivery) runnerImage else image
        val requested = text(body["Image"])
        require(requested != null && (allowed == null || requested == allowed), "A container's image is refused.")
        require(USER.matches(text(body["User"]).orEmpty()), "A container must run as a non-root uid and gid.")
        val host = body["HostConfig"] as JsonObject
        require(host.keys.all { it in HOST_KEYS }, "A container asks for a host setting a session never needs.")
        val binds = hostBinds(container)
        require(binds.all { BIND.matches(it) }, "A container may mount the session's volumes only.")
        val binaries = binds.filter { BINARY_BIND.matches(it) }
        val execution = container.name.removePrefix("ostrom-s-").split('-').take(2).joinToString("-")
        val own = "ostrom-ws-$execution$DELIVERY_SUFFIX:"
        require(binaries.all { it.startsWith(own) }, "A container mounts its own execution's binary only.")
        // The delivery's invariant beyond the guard: the volume it fills is one the plan creates.
        require(
            binaries.all { bind -> bind.substringBefore(':') in plan.volumes.map { it.name } },
            "The binary volume must be a planned volume.",
        )
        if (delivery) {
            require(
                binds.size == 1 && binaries.size == 1,
                "A delivery container mounts its execution's binary volume only.",
            )
            require(binaries.none { it.endsWith(":ro") }, "A delivery container fills its binary volume.")
        } else {
            require(binaries.all { it.endsWith(":ro") }, "A session's container mounts the binary read-only.")
        }
        require("ALL" in texts(host["CapDrop"]), "A container must drop every capability.")
        require("no-new-privileges" in texts(host["SecurityOpt"]), "A container must forbid new privileges.")
    }
}.distinct()

private fun hostBinds(container: PlannedObject): List<String> =
    texts((container.body["HostConfig"] as JsonObject)["Binds"]).filterNotNull()

private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun texts(element: JsonElement?): List<String?> = (element as? JsonArray)?.map(::text).orEmpty()
