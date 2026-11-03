// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A plan as the server computes it (DockerExecutionPlan), for the runner's tests. */
internal fun plan(session: Uuid, epoch: Long, token: String = "ostrom_runner_xyz"): ExecutionPlan {
    val hex = session.toHexString()
    val volumes = listOf("ostrom-ws-$hex", "ostrom-ws-$hex-socket", "ostrom-ws-$hex-checks").map { name ->
        PlannedObject(
            name,
            buildJsonObject {
                put("Name", name)
                putJsonObject("Labels") { put("ostrom.run/session", hex) }
            },
        )
    }
    val execution = "ostrom-s-$hex-e$epoch"
    val users = listOf("$execution-agent" to "1000:1000", "$execution-checks" to "1000:1000", execution to "1001:1001")
    val containers = users.map { (name, user) ->
        PlannedObject(name, container(hex, epoch, user, token.takeIf { name == execution }))
    }
    return ExecutionPlan(volumes, containers)
}

private fun container(hex: String, epoch: Long, user: String, token: String?) = buildJsonObject {
    put("Image", "ghcr.io/ostrom-hq/ostrom-runner:1")
    put("User", user)
    putJsonArray("Entrypoint") { add(JsonPrimitive("/usr/local/bin/ostrom-runner")) }
    put("WorkingDir", "/workspace")
    putJsonArray("Env") { token?.let { add(JsonPrimitive("OSTROM_RUNNER_TOKEN=$it")) } }
    putJsonObject("Labels") {
        put("ostrom.run/session", hex)
        put("ostrom.run/epoch", epoch.toString())
    }
    putJsonObject("HostConfig") {
        putJsonArray("Binds") { add(JsonPrimitive("ostrom-ws-$hex:/workspace")) }
        putJsonArray("GroupAdd") { add(JsonPrimitive("2000")) }
        putJsonArray("ExtraHosts") { add(JsonPrimitive("host.docker.internal:host-gateway")) }
        putJsonObject("RestartPolicy") { put("Name", "no") }
        putJsonArray("CapDrop") { add(JsonPrimitive("ALL")) }
        putJsonArray("SecurityOpt") { add(JsonPrimitive("no-new-privileges")) }
        put("ReadonlyRootfs", true)
        putJsonObject("Tmpfs") { put("/tmp", "rw,nosuid,size=1g") }
        put("PidsLimit", 4096)
        put("Memory", 1024)
        put("NanoCpus", 2)
    }
}

class PlanGuardTest {
    private val runner = Uuid.random()
    private val session = Uuid.random()
    private val guard = PlanGuard(runner, null, null)

    private fun JsonObject.with(key: String, value: JsonElement?) =
        JsonObject(if (value == null) this - key else this + (key to value))

    private fun hostWith(key: String, value: JsonElement?): ExecutionPlan {
        val sent = plan(session, 1)
        val runnerContainer = sent.containers.last()
        val host = runnerContainer.body["HostConfig"]!!.jsonObject.with(key, value)
        val changed = PlannedObject(runnerContainer.name, runnerContainer.body.with("HostConfig", host))
        return sent.copy(containers = listOf(changed))
    }

    private fun PlannedObject.label(key: String) = body["Labels"]!!.jsonObject[key]!!.jsonPrimitive.content

    private fun PlannedObject.network() = body["HostConfig"]!!.jsonObject["NetworkMode"]?.jsonPrimitive?.content

    @Test
    fun aSessionsPlanIsAcceptedAndMarkedAsThisRunners() {
        val sent = plan(session, 1)
        val checked = guard.check(sent)
        assertEquals(sent.volumes.map { it.name }, checked.volumes.map { it.name })
        for (planned in checked.volumes + checked.containers) {
            assertEquals(runner.toString(), planned.label(PlanGuard.RUNNER_LABEL))
        }
        assertEquals(session.toHexString(), checked.volumes[0].label("ostrom.run/session"))
        assertNull(checked.containers.last().network(), "The engine's default network")

        val placed = PlanGuard(runner, "sessions", "ghcr.io/ostrom-hq/ostrom-runner:1").check(sent)
        assertEquals("sessions", placed.containers.last().network())

        // A volume without labels gets this runner's.
        val name = "ostrom-ws-${session.toHexString()}"
        val bare = PlannedObject(name, buildJsonObject { put("Name", name) })
        val labelled = guard.check(ExecutionPlan(listOf(bare), emptyList())).volumes.single()
        assertEquals(runner.toString(), labelled.label(PlanGuard.RUNNER_LABEL))
    }

    @Test
    fun anythingButASessionIsRefused() {
        val hex = session.toHexString()
        val sent = plan(session, 1)
        val volume = sent.volumes.first()
        val runnerContainer = sent.containers.last()
        fun containerWith(key: String, value: JsonElement?) =
            sent.copy(containers = listOf(PlannedObject(runnerContainer.name, runnerContainer.body.with(key, value))))
        fun volumeWith(key: String, value: JsonElement) =
            sent.copy(volumes = listOf(PlannedObject(volume.name, volume.body.with(key, value))))
        val refused = mapOf(
            "a volume of another name" to sent.copy(volumes = listOf(PlannedObject("data", volume.body))),
            "a volume with a driver" to volumeWith("Driver", JsonPrimitive("local")),
            "a volume whose body names another" to volumeWith("Name", JsonPrimitive("other")),
            "a container of another name" to
                sent.copy(containers = listOf(PlannedObject("miner", runnerContainer.body))),
            "a command" to containerWith("Cmd", JsonPrimitive("sh")),
            "no image" to containerWith("Image", null),
            "an image that is not text" to containerWith("Image", JsonPrimitive(1)),
            "root" to containerWith("User", JsonPrimitive("0:0")),
            "no user" to containerWith("User", null),
            "no limits" to containerWith("HostConfig", null),
            "privileged" to hostWith("Privileged", JsonPrimitive(true)),
            "the host's network" to hostWith("NetworkMode", JsonPrimitive("host")),
            "a host path" to hostWith("Binds", JsonArray(listOf(JsonPrimitive("/:/host")))),
            "capabilities kept" to hostWith("CapDrop", null),
            "new privileges" to hostWith("SecurityOpt", JsonArray(emptyList())),
        )
        for ((why, bad) in refused) assertFailsWith<IllegalArgumentException>(why) { guard.check(bad) }
        assertFailsWith<IllegalArgumentException>("another image than the machine's") {
            PlanGuard(runner, null, "ghcr.io/ostrom-hq/ostrom-runner:2").check(sent)
        }
        assertEquals("ostrom-ws-$hex", guard.check(sent).volumes.first().name)
    }

    @Test
    fun aPlansTokenIsNeverPrinted() {
        val sent = plan(session, 1, token = "secret-token")
        assertEquals("PlannedObject(name=ostrom-s-${session.toHexString()}-e1)", sent.containers.last().toString())
    }
}
