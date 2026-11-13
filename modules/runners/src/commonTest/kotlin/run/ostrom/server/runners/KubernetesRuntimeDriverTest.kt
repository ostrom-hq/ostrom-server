// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.fakes.FakeApi
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.KubernetesApiException
import run.ostrom.server.runners.infrastructure.KubernetesRuntimeDriver
import run.ostrom.server.runners.infrastructure.KubernetesSettings
import run.ostrom.server.runners.infrastructure.SessionNetworkPolicy
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KubernetesRuntimeDriverTest {
    private val api = FakeApi()
    private var tokenReads = 0
    private val settings = KubernetesSettings("https://k8s/", "ostrom", "/t", "/c", "fast", "5Gi", "ghcr")
    private val driver = KubernetesRuntimeDriver(
        api.client,
        settings,
        "http://ostrom-server.ostrom.svc:8080",
        "img:1",
    ) { "sa-${++tokenReads}" }
    private val session = session().copy(epoch = 4)
    private val hex = session.id.toHexString()
    private val ns = "/api/v1/namespaces/ostrom"

    private fun spec(
        restricted: Boolean = true,
        repository: RepositorySpec? = RepositorySpec("https://github.com/acme/api", "main"),
    ) =
        ExecutionSpec(
            session.id,
            ORG_A,
            4,
            AgentKind.CLAUDE,
            repository,
            "ostrom_runner_abc",
            CloudPlan.FREE.sessionLimits().copy(restrictedEgress = restricted),
            model = "claude-opus-5-5".takeIf { restricted },
            prompt = "Fix the tests".takeIf { restricted },
        )

    private fun body(index: Int): JsonObject = Json.parseToJsonElement(api.calls[index].body!!).jsonObject

    @Test
    fun aWorkspaceIsAClaimInTheNamespaceAndAnExistingOneIsFine() = runTest {
        driver.ensureWorkspace(session)
        api.on("POST", "$ns/persistentvolumeclaims", HttpStatusCode.Conflict to "{}")
        driver.ensureWorkspace(session)

        assertEquals(listOf("POST $ns/persistentvolumeclaims", "POST $ns/persistentvolumeclaims"), api.lines())
        val claim = body(0)
        assertEquals("ostrom-ws-$hex", claim["metadata"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            hex,
            claim["metadata"]!!.jsonObject["labels"]!!.jsonObject["ostrom.run/session"]!!.jsonPrimitive.content,
        )
        assertEquals("fast", claim["spec"]!!.jsonObject["storageClassName"]!!.jsonPrimitive.content)
        val requests = claim["spec"]!!.jsonObject["resources"]!!.jsonObject["requests"]!!.jsonObject
        assertEquals("5Gi", requests["storage"]!!.jsonPrimitive.content)
        assertEquals("Bearer sa-1", api.calls[0].authorization)
        assertEquals("Bearer sa-2", api.calls[1].authorization)
    }

    @Test
    fun aClaimWithoutStorageClassUsesTheClusterDefault() = runTest {
        KubernetesRuntimeDriver(
            api.client,
            settings.copy(storageClass = null, imagePullSecret = null),
            "http://s",
            "img",
        ) { "t" }.ensureWorkspace(session)
        assertNull(body(0)["spec"]!!.jsonObject["storageClassName"])
    }

    @Test
    fun anExecutionIsPoliciesThenASecretThenABarePod() = runTest {
        driver.startExecution(spec())

        val policies = "/apis/networking.k8s.io/v1/namespaces/ostrom/networkpolicies"
        assertEquals(listOf("POST $policies", "POST $policies", "POST $ns/secrets", "POST $ns/pods"), api.lines())
        val secret = body(2)
        assertEquals("ostrom_runner_abc", secret["stringData"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals("Fix the tests", secret["stringData"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        val pod = body(3)
        val metadata = pod["metadata"]!!.jsonObject
        assertEquals("ostrom-s-$hex-e4", metadata["name"]!!.jsonPrimitive.content)
        assertEquals("restricted", metadata["labels"]!!.jsonObject["ostrom.run/egress"]!!.jsonPrimitive.content)
        assertEquals("4", metadata["labels"]!!.jsonObject["ostrom.run/epoch"]!!.jsonPrimitive.content)
        assertEquals(
            "20M",
            metadata["annotations"]!!.jsonObject["kubernetes.io/egress-bandwidth"]!!.jsonPrimitive.content,
        )
        val spec = pod["spec"]!!.jsonObject
        assertEquals("Never", spec["restartPolicy"]!!.jsonPrimitive.content)
        assertEquals("false", spec["automountServiceAccountToken"]!!.jsonPrimitive.content)
        assertEquals("ghcr", spec["imagePullSecrets"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val container = spec["containers"]!!.jsonArray[0].jsonObject
        assertEquals("img:1", container["image"]!!.jsonPrimitive.content)
        val variables = container["env"]!!.jsonArray.map { it.jsonObject }
        val env = variables.filter { "value" in it }.associate {
            it["name"]!!.jsonPrimitive.content to it["value"]!!.jsonPrimitive.content
        }
        // The token and the first message come from the Secret, by reference: in neither the spec nor a file.
        val fromSecret = variables.filter { "valueFrom" in it }.associate {
            val reference = it["valueFrom"]!!.jsonObject["secretKeyRef"]!!.jsonObject
            it["name"]!!.jsonPrimitive.content to
                (reference["name"]!!.jsonPrimitive.content to reference["key"]!!.jsonPrimitive.content)
        }
        assertEquals("ostrom-s-$hex-e4" to "token", fromSecret["OSTROM_RUNNER_TOKEN"])
        assertEquals("ostrom-s-$hex-e4" to "prompt", fromSecret["OSTROM_AGENT_PROMPT"])
        assertNull(env["OSTROM_RUNNER_TOKEN"])
        assertEquals("/run/ostrom/agent.sock", env["OSTROM_AGENT_SOCKET"])
        assertEquals("https://github.com/acme/api", env["OSTROM_REPOSITORY_URL"])
        assertEquals("main", env["OSTROM_REPOSITORY_REF"])
        assertEquals("claude", env["OSTROM_AGENT_COMMAND"])
        assertEquals("4", env["OSTROM_EPOCH"])
        assertEquals("claude-opus-5-5", env["OSTROM_AGENT_MODEL"])
        assertFalse("Fix the tests" in pod.toString())
        assertFalse("ostrom_runner_abc" in pod.toString())
    }

    @Test
    fun theSessionPodIsHardened() = runTest {
        driver.startExecution(spec())
        val spec = body(3)["spec"]!!.jsonObject
        val container = spec["initContainers"]!!.jsonArray[0].jsonObject
        val claimName = spec["volumes"]!!.jsonArray[0].jsonObject["persistentVolumeClaim"]!!.jsonObject["claimName"]
        assertEquals("ostrom-ws-$hex", claimName!!.jsonPrimitive.content)
        assertEquals(
            "1Gi",
            spec["volumes"]!!.jsonArray[1].jsonObject["emptyDir"]!!.jsonObject["sizeLimit"]!!.jsonPrimitive.content,
        )
        // No volume carries the token.
        assertFalse(spec["volumes"]!!.jsonArray.any { "secret" in it.jsonObject })
        assertEquals(
            "true",
            container["securityContext"]!!.jsonObject["readOnlyRootFilesystem"]!!.jsonPrimitive.content,
        )
        val tmp = container["volumeMounts"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "tmp" }
        assertEquals("/tmp", tmp["mountPath"]!!.jsonPrimitive.content)
        assertEquals(
            "2Gi",
            container["resources"]!!.jsonObject["limits"]!!.jsonObject["ephemeral-storage"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "RuntimeDefault",
            spec["securityContext"]!!.jsonObject["seccompProfile"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertNull(spec["priorityClassName"])
    }

    @Test
    fun theAgentAndTheCheckHostRunInTheirOwnContainersWithoutTheToken() = runTest {
        driver.startExecution(spec())
        val spec = body(3)["spec"]!!.jsonObject
        assertEquals("false", spec["shareProcessNamespace"]!!.jsonPrimitive.content)
        val pod = spec["securityContext"]!!.jsonObject
        assertNull(pod["runAsUser"])
        assertEquals("2000", pod["fsGroup"]!!.jsonPrimitive.content)
        val (agent, checks) = spec["initContainers"]!!.jsonArray.map { it.jsonObject }
        val runner = spec["containers"]!!.jsonArray.single().jsonObject
        assertEquals("agent", agent["name"]!!.jsonPrimitive.content)
        assertEquals("Always", agent["restartPolicy"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/usr/local/bin/ostrom-runner", "agent-host"),
            agent["command"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        fun uid(container: JsonObject) = container["securityContext"]!!.jsonObject["runAsUser"]!!.jsonPrimitive.content
        assertEquals("1000", uid(agent))
        assertEquals("1001", uid(runner))
        assertEquals("checks", checks["name"]!!.jsonPrimitive.content)
        assertEquals("Always", checks["restartPolicy"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/usr/local/bin/ostrom-runner", "check-host"),
            checks["command"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("1000", uid(checks))
        assertFalse("secretKeyRef" in checks.toString())
        assertTrue("/run/ostrom-check/check.sock" in checks.toString())
        // The agent's container has no variable from the Secret, and only the runner's has the server's address.
        assertFalse("secretKeyRef" in agent.toString())
        assertFalse("OSTROM_SERVER_URL" in agent.toString())
        assertTrue("secretKeyRef" in runner.toString())
        fun mounts(container: JsonObject) =
            container["volumeMounts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        // The agent never mounts the check socket, the check host never the agent socket.
        assertEquals(listOf("workspace", "tmp", "agent-socket"), mounts(agent))
        assertEquals(listOf("workspace", "check-tmp", "check-socket"), mounts(checks))
        assertEquals(listOf("agent-socket", "check-socket"), mounts(runner))
        assertEquals(
            listOf("workspace", "tmp", "agent-socket", "check-tmp", "check-socket"),
            spec["volumes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
        // The agent's gh configuration lives with the sockets, in memory only.
        fun volume(name: String) = spec["volumes"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == name }["emptyDir"]!!.jsonObject
        assertEquals("Memory", volume("agent-socket")["medium"]!!.jsonPrimitive.content)
        assertNull(volume("tmp")["medium"])
    }

    @Test
    fun thePoliciesAreAppliedOncePerProcess() = runTest {
        driver.startExecution(spec())
        driver.startExecution(
            spec(restricted = false, repository = RepositorySpec("https://github.com/acme/api", null)),
        )
        assertEquals(listOf("POST $ns/secrets", "POST $ns/pods"), api.lines().drop(4))
        val open = body(5)
        assertEquals(
            "open",
            open["metadata"]!!.jsonObject["labels"]!!.jsonObject["ostrom.run/egress"]!!.jsonPrimitive.content,
        )
        assertNull(open["metadata"]!!.jsonObject["annotations"])
        assertNull(body(4)["stringData"]!!.jsonObject["prompt"])
        assertFalse("OSTROM_AGENT_PROMPT" in open.toString())
        assertFalse("OSTROM_REPOSITORY_REF" in open["spec"]!!.jsonObject["containers"]!!.jsonArray[0].toString())
    }

    @Test
    fun sessionPodsTakeTheConfiguredLowPriorityClass() = runTest {
        val low = KubernetesRuntimeDriver(
            api.client,
            settings.copy(priorityClass = "ostrom-low", imagePullSecret = null),
            "http://s",
            "img:1",
        ) { "t" }
        low.startExecution(spec(repository = null))
        assertEquals("ostrom-low", body(3)["spec"]!!.jsonObject["priorityClassName"]!!.jsonPrimitive.content)
        assertNull(body(3)["spec"]!!.jsonObject["imagePullSecrets"])
    }

    @Test
    fun existingPoliciesAreReplacedAtTheirVersion() = runTest {
        val policies = "/apis/networking.k8s.io/v1/namespaces/ostrom/networkpolicies"
        api.on("POST", policies, HttpStatusCode.Conflict to "{}")
        val versioned = """{"metadata":{"resourceVersion":"41"}}"""
        api.on("GET", "$policies/ostrom-sessions-restricted", HttpStatusCode.OK to versioned)
        api.on("GET", "$policies/ostrom-sessions-open", HttpStatusCode.OK to """{"metadata":{}}""")
        driver.startExecution(spec(repository = null))

        assertEquals("PUT $policies/ostrom-sessions-restricted", api.lines()[2])
        assertEquals("41", body(2)["metadata"]!!.jsonObject["resourceVersion"]!!.jsonPrimitive.content)
        assertEquals("PUT $policies/ostrom-sessions-open", api.lines()[5])
    }

    @Test
    fun aRefusedCallFailsWithoutTheApisBody() = runTest {
        api.on("POST", "$ns/pods", HttpStatusCode.Forbidden to """{"message":"secret detail"}""")
        val error = assertFailsWith<KubernetesApiException> { driver.startExecution(spec()) }
        assertEquals("Kubernetes API: create Pod answered 403.", error.message)

        val policies = "/apis/networking.k8s.io/v1/namespaces/ostrom/networkpolicies"
        val other = KubernetesRuntimeDriver(api.client, settings, "http://s", "img") { "t" }
        api.on("POST", policies, HttpStatusCode.Conflict to "{}")
        api.on("GET", "$policies/ostrom-sessions-restricted", HttpStatusCode.Forbidden to "{}")
        assertFailsWith<KubernetesApiException> { other.startExecution(spec()) }
        api.on("POST", policies, HttpStatusCode.Forbidden to "{}")
        assertFailsWith<KubernetesApiException> { other.startExecution(spec()) }
    }

    @Test
    fun podPhasesMapToExecutionStates() = runTest {
        val path = "$ns/pods/ostrom-s-$hex-e4"
        fun pod(phase: String, waiting: String? = null): Pair<HttpStatusCode, String> {
            val state = if (waiting != null) """{"waiting":{"reason":"$waiting"}}""" else "{}"
            return HttpStatusCode.OK to """{"status":{"phase":"$phase","containerStatuses":[{"state":$state}]}}"""
        }
        val cases = listOf(
            pod("Pending") to ExecutionStatus.PENDING,
            pod("Pending", "ContainerCreating") to ExecutionStatus.PENDING,
            pod("Pending", "InvalidImageName") to ExecutionStatus.FAILED,
            pod("Running") to ExecutionStatus.RUNNING,
            pod("Succeeded") to ExecutionStatus.ENDED,
            pod("Failed") to ExecutionStatus.ENDED,
            (HttpStatusCode.OK to "{}") to ExecutionStatus.PENDING,
            (HttpStatusCode.OK to """{"status":{}}""") to ExecutionStatus.PENDING,
            (HttpStatusCode.OK to """{"status":"odd"}""") to ExecutionStatus.PENDING,
            (HttpStatusCode.OK to """{"status":{"phase":"Running","containerStatuses":3}}""") to
                ExecutionStatus.RUNNING,
            (HttpStatusCode.NotFound to "{}") to ExecutionStatus.MISSING,
        )
        for ((answer, expected) in cases) {
            api.on("GET", path, answer)
            assertEquals(expected, driver.executionStatus(session), answer.second)
        }
        api.on("GET", path, HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<KubernetesApiException> { driver.executionStatus(session) }
    }

    @Test
    fun stoppingDeletesThePodsAndSecretsOfTheSessionOnly() = runTest {
        driver.stopExecutions(session)

        assertEquals(listOf("DELETE $ns/pods", "DELETE $ns/secrets"), api.lines())
        assertEquals("ostrom.run/session=$hex", api.calls[0].query["labelSelector"])
        assertEquals("20", api.calls[0].query["gracePeriodSeconds"])
        assertNull(api.calls[1].query["gracePeriodSeconds"])
        api.on("DELETE", "$ns/secrets", HttpStatusCode.Forbidden to "{}")
        assertFailsWith<KubernetesApiException> { driver.stopExecutions(session) }
    }

    @Test
    fun aWorkspaceIsGoneOnlyOnceTheApiNoLongerHasIt() = runTest {
        val path = "$ns/persistentvolumeclaims/ostrom-ws-$hex"
        api.on("GET", path, HttpStatusCode.OK to "{}")
        assertFalse(driver.deleteWorkspace(session))
        api.on("GET", path, HttpStatusCode.NotFound to "{}")
        assertTrue(driver.deleteWorkspace(session))
        api.on("DELETE", path, HttpStatusCode.NotFound to "{}")
        assertTrue(driver.deleteWorkspace(session))
        api.on("DELETE", path, HttpStatusCode.Forbidden to "{}")
        assertFailsWith<KubernetesApiException> { driver.deleteWorkspace(session) }
    }

    @Test
    fun thePoliciesBlockPrivateRangesAndRestrictPorts() {
        val selector = mapOf("app.kubernetes.io/name" to "ostrom", "app.kubernetes.io/instance" to "prod")
        val restricted = SessionNetworkPolicy.manifest("r", restricted = true, serverSelector = selector)
        val egress = restricted["spec"]!!.jsonObject["egress"]!!.jsonArray
        assertEquals(3, egress.size)
        val server = egress[1].jsonObject["to"]!!.jsonArray[0].jsonObject["podSelector"]!!.jsonObject["matchLabels"]!!
        assertEquals(selector, server.jsonObject.mapValues { it.value.jsonPrimitive.content })
        val internet = egress[2].jsonObject
        val v4 = internet["to"]!!.jsonArray[0].jsonObject["ipBlock"]!!.jsonObject
        assertEquals("0.0.0.0/0", v4["cidr"]!!.jsonPrimitive.content)
        val blocked = v4["except"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(blocked.containsAll(listOf("10.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16")))
        assertEquals(
            listOf(80, 443, 22, 9418),
            internet["ports"]!!.jsonArray.map { it.jsonObject["port"]!!.jsonPrimitive.content.toInt() },
        )
        val podSelector = restricted["spec"]!!.jsonObject["podSelector"]!!.jsonObject["matchLabels"]!!.jsonObject
        assertEquals("restricted", podSelector["ostrom.run/egress"]!!.jsonPrimitive.content)
        assertEquals(0, restricted["spec"]!!.jsonObject["ingress"]!!.jsonArray.size)

        val open = SessionNetworkPolicy.manifest("o", restricted = false, serverSelector = selector)
        assertNull(open["spec"]!!.jsonObject["egress"]!!.jsonArray[2].jsonObject["ports"])
    }
}
