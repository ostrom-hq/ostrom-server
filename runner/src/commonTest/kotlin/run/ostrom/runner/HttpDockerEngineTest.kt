// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/** The Engine API as a registered runner calls it, answered call by call. */
class HttpDockerEngineTest {
    private val runner = Uuid.random()
    private val session = Uuid.random()
    private val hex = session.toHexString()
    private val calls = mutableListOf<String>()
    private val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()

    private val docker = HttpDockerEngine(
        HttpClient(
            MockEngine { request ->
                val call = "${request.method.value} ${request.url.encodedPath}"
                val query = request.url.parameters.entries().sortedBy { it.key }
                    .joinToString("&") { (key, values) -> "$key=${values.joinToString(",")}" }
                val body = request.body.toByteArray().decodeToString()
                calls += listOf(call, query, body).filter(String::isNotEmpty).joinToString(" ")
                val (status, answer) = answers[call] ?: (HttpStatusCode.NoContent to "")
                respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { expectSuccess = false },
        "http://docker:2375/",
        runner,
    )

    private val mine = """filters={"label":["ostrom.run/runner=$runner"]}"""

    @Test
    fun itListsOnlyThisRunnersObjectsAndSkipsWhatIsNotASessions() = runTest {
        answers["GET /containers/json"] = HttpStatusCode.OK to """[
            {"Id":"c1","Names":["/ostrom-s-$hex-e2"],"State":"running",
             "Labels":{"ostrom.run/session":"$hex","ostrom.run/epoch":"2"},"Image":"x"},
            {"Id":"c2","State":"exited","Labels":{"ostrom.run/session":"$hex","ostrom.run/epoch":"3"}},
            {"Id":"c3","Names":["/x"],"Labels":{"ostrom.run/session":"not-hex","ostrom.run/epoch":"1"}},
            {"Id":"c4","Names":["/x"],"Labels":{"ostrom.run/session":"$hex","ostrom.run/epoch":"one"}},
            {"Id":"c5","Names":["/x"],"Labels":{"ostrom.run/session":"$hex"}},
            {"Id":"c6","Names":["/x"]}
        ]"""
        assertEquals(
            listOf(
                HeldContainer("c1", "ostrom-s-$hex-e2", session, 2, "running"),
                HeldContainer("c2", "", session, 3, "exited"),
            ),
            docker.containers(),
        )
        assertEquals(listOf("GET /containers/json all=true&$mine"), calls)

        answers["GET /volumes"] = HttpStatusCode.OK to """{"Volumes":[
            {"Name":"ostrom-ws-$hex","Labels":{"ostrom.run/session":"$hex"},"Driver":"local"},
            {"Name":"v2","Labels":{"ostrom.run/session":"nope"}},
            {"Name":"v3","Labels":{}},
            {"Name":"v4","Labels":null}
        ],"Warnings":null}"""
        assertEquals(listOf(HeldVolume("ostrom-ws-$hex", session)), docker.volumes())
        assertEquals("GET /volumes $mine", calls.last())

        answers["GET /volumes"] = HttpStatusCode.OK to """{"Volumes":null}"""
        assertEquals(emptyList(), docker.volumes())

        answers["GET /containers/json"] = HttpStatusCode.InternalServerError to "{}"
        assertEquals(
            "Docker Engine API: list containers answered 500.",
            assertFailsWith<DockerApiException> { docker.containers() }.message,
        )
        answers["GET /volumes"] = HttpStatusCode.Forbidden to "{}"
        assertFailsWith<DockerApiException> { docker.volumes() }
    }

    @Test
    fun aContainerIsCreatedPullingItsImageOnceThenStarted() = runTest {
        val name = "ostrom-s-$hex-e1"
        val body = buildJsonObject { put("Image", "ghcr.io/ostrom-hq/ostrom-runner:1") }
        val container = PlannedObject(name, body)

        docker.startContainer(container)
        assertEquals(listOf("POST /containers/create name=$name $body", "POST /containers/$name/start"), calls)

        // The engine does not have the image: pulled, then created.
        calls.clear()
        var creations = 0
        val pulling = HttpDockerEngine(
            HttpClient(
                MockEngine { request ->
                    val call = "${request.method.value} ${request.url.encodedPath}"
                    calls += call + " " + request.url.parameters.entries().sortedBy { it.key }
                        .joinToString("&") { (key, values) -> "$key=${values.joinToString(",")}" }
                    val status = when {
                        call == "POST /containers/create" && creations++ == 0 -> HttpStatusCode.NotFound
                        call == "POST /containers/create" -> HttpStatusCode.Created
                        call == "POST /images/create" -> HttpStatusCode.OK
                        else -> HttpStatusCode.NotModified
                    }
                    respond("{}", status)
                },
            ) { expectSuccess = false },
            "http://docker:2375",
            runner,
        )
        pulling.startContainer(container)
        assertEquals(
            listOf(
                "POST /containers/create name=$name",
                "POST /images/create fromImage=ghcr.io/ostrom-hq/ostrom-runner&tag=1",
                "POST /containers/create name=$name",
                "POST /containers/$name/start ",
            ),
            calls,
        )

        // It exists already, from a pass cut short: started.
        calls.clear()
        answers["POST /containers/create"] = HttpStatusCode.Conflict to "{}"
        docker.startContainer(container)
        assertEquals("POST /containers/$name/start", calls.last())

        answers["POST /containers/create"] = HttpStatusCode.NotFound to "{}"
        answers["POST /images/create"] = HttpStatusCode.NotFound to "{}"
        assertEquals(
            "Docker Engine API: pull image answered 404.",
            assertFailsWith<DockerApiException> { docker.startContainer(container) }.message,
        )
        answers["POST /containers/create"] = HttpStatusCode.BadRequest to "{}"
        assertFailsWith<DockerApiException> { docker.startContainer(container) }
        answers.remove("POST /containers/create")
        answers["POST /containers/$name/start"] = HttpStatusCode.InternalServerError to "{}"
        assertEquals(
            "Docker Engine API: start container answered 500.",
            assertFailsWith<DockerApiException> { docker.startContainer(container) }.message,
        )
    }

    @Test
    fun theMachinesProcessorsAndMemoryAreReadFromTheEngine() = runTest {
        answers["GET /info"] = HttpStatusCode.OK to """{"NCPU":8,"MemTotal":34359738368,"Name":"m"}"""
        assertEquals(EngineInfo(8, 34_359_738_368), docker.info())
        answers["GET /info"] = HttpStatusCode.InternalServerError to "{}"
        assertFailsWith<DockerApiException> { docker.info() }
    }

    @Test
    fun aVolumeIsCreatedFromItsBody() = runTest {
        val volume = PlannedObject("ostrom-ws-$hex", buildJsonObject { put("Name", "ostrom-ws-$hex") })
        docker.createVolume(volume)
        assertEquals(listOf("""POST /volumes/create {"Name":"ostrom-ws-$hex"}"""), calls)
        answers["POST /volumes/create"] = HttpStatusCode.InternalServerError to "{}"
        assertFailsWith<DockerApiException> { docker.createVolume(volume) }
    }

    @Test
    fun removingWhatIsAlreadyGoneIsFine() = runTest {
        docker.removeContainer("c1")
        assertEquals(listOf("POST /containers/c1/stop t=10", "DELETE /containers/c1 force=true"), calls)

        for (stop in listOf(HttpStatusCode.NotModified, HttpStatusCode.NotFound)) {
            answers["POST /containers/c1/stop"] = stop to "{}"
            answers["DELETE /containers/c1"] = HttpStatusCode.NotFound to "{}"
            docker.removeContainer("c1")
        }
        answers["DELETE /containers/c1"] = HttpStatusCode.InternalServerError to "{}"
        assertFailsWith<DockerApiException> { docker.removeContainer("c1") }
        answers["POST /containers/c1/stop"] = HttpStatusCode.InternalServerError to "{}"
        assertFailsWith<DockerApiException> { docker.removeContainer("c1") }

        docker.removeVolume("v1")
        assertEquals("DELETE /volumes/v1", calls.last())
        for (gone in listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict)) {
            answers["DELETE /volumes/v1"] = gone to "{}"
            docker.removeVolume("v1")
        }
        answers["DELETE /volumes/v1"] = HttpStatusCode.InternalServerError to "{}"
        assertFailsWith<DockerApiException> { docker.removeVolume("v1") }
    }

    @Test
    fun anImageReferenceSplitsIntoNameAndTag() {
        assertEquals("ghcr.io/o/r" to "1.2", HttpDockerEngine.splitImage("ghcr.io/o/r:1.2"))
        assertEquals("o/r" to "sha256:abc", HttpDockerEngine.splitImage("o/r@sha256:abc"))
        assertEquals("localhost:5000/r" to "latest", HttpDockerEngine.splitImage("localhost:5000/r"))
        assertEquals("r" to "latest", HttpDockerEngine.splitImage("r"))
    }
}
