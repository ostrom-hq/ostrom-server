// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.PodTokenIdentity
import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.fakes.FakeApi
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.infrastructure.KubernetesApiException
import run.ostrom.server.runners.infrastructure.KubernetesClusters
import run.ostrom.server.runners.infrastructure.KubernetesRuntimeDriver
import run.ostrom.server.runners.infrastructure.KubernetesSettings
import run.ostrom.server.runners.infrastructure.KubernetesTarget
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
import kotlin.uuid.Uuid

/** The pods' projected token and its TokenReview, on the Kubernetes driver (TeamCode's `KubernetesClusterPortTest`). */
class KubernetesPodIdentityTest {
    private val api = FakeApi()
    private val clusters = object : KubernetesClusters {
        override suspend fun <T> use(clusterId: String, block: suspend (KubernetesTarget) -> T): T {
            if (clusterId != CLUSTER) throw RuntimeUnreachableException("gone")
            return block(KubernetesTarget(api.client, "https://k8s/", "sa", 1))
        }
    }
    private val delivery = RunnerDeliverySource {
        RunnerDelivery("runner:1", RunnerDelivery.DEFAULT_RUNNER_BINARY, "unused:1", "https://ostrom.example")
    }
    private val profile =
        SessionProfile(clusterId = CLUSTER, namespace = "ostrom", image = "img:1", workspaceGibibytes = 5)
    private val sessionId = Uuid.random()
    private val spec = ExecutionSpec(
        sessionId,
        ORG_A,
        2,
        AgentKind.CLAUDE,
        null,
        "ostrom_runner_abc",
        CloudPlan.FREE.sessionLimits(),
        profile = profile,
    )
    private val reviews = "/apis/authentication.k8s.io/v1/tokenreviews"

    private fun driver(podIdentity: Boolean) =
        KubernetesRuntimeDriver(clusters, KubernetesSettings(podIdentity = podIdentity), delivery)

    private suspend fun pod(podIdentity: Boolean): JsonObject {
        driver(podIdentity).startExecution(spec)
        val created = api.calls.single { it.method == "POST" && it.path.endsWith("/pods") }
        return Json.parseToJsonElement(created.body!!).jsonObject["spec"]!!.jsonObject
    }

    private fun JsonObject.names(key: String): List<String> =
        this[key]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    @Test
    fun withPodIdentityOnlyTheRunnerMountsAProjectedTokenOfTheOstromAudience() = runTest {
        val spec = pod(podIdentity = true)
        val volume = spec["volumes"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == KubernetesRuntimeDriver.POD_TOKEN_VOLUME }
        val source = volume["projected"]!!.jsonObject["sources"]!!.jsonArray.single().jsonObject
        val token = source["serviceAccountToken"]!!.jsonObject
        assertEquals("ostrom", token["audience"]!!.jsonPrimitive.content)
        assertEquals("600", token["expirationSeconds"]!!.jsonPrimitive.content)
        assertEquals("token", token["path"]!!.jsonPrimitive.content)
        assertEquals("false", spec["automountServiceAccountToken"]!!.jsonPrimitive.content)

        val runner = spec["containers"]!!.jsonArray.single().jsonObject
        val mount = runner["volumeMounts"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == KubernetesRuntimeDriver.POD_TOKEN_VOLUME }
        assertEquals("/var/run/secrets/ostrom", mount["mountPath"]!!.jsonPrimitive.content)
        assertEquals("true", mount["readOnly"]!!.jsonPrimitive.content)
        val file = runner["env"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "OSTROM_POD_TOKEN_FILE" }
        assertEquals("/var/run/secrets/ostrom/token", file["value"]!!.jsonPrimitive.content)
        // The runner token stays, from the Secret.
        assertTrue("OSTROM_RUNNER_TOKEN" in runner.names("env"))
        // Neither the install, the agent nor the check host sees it.
        for (container in spec["initContainers"]!!.jsonArray.map { it.jsonObject }) {
            assertFalse(KubernetesRuntimeDriver.POD_TOKEN_VOLUME in container.names("volumeMounts"))
            assertFalse("OSTROM_POD_TOKEN_FILE" in container["env"]?.let { container.names("env") }.orEmpty())
        }
    }

    @Test
    fun withoutPodIdentityThePodHasNoProjectedToken() = runTest {
        val spec = pod(podIdentity = false)
        assertFalse(KubernetesRuntimeDriver.POD_TOKEN_VOLUME in spec.names("volumes"))
        val runner = spec["containers"]!!.jsonArray.single().jsonObject
        assertFalse(KubernetesRuntimeDriver.POD_TOKEN_VOLUME in runner.names("volumeMounts"))
        assertFalse("OSTROM_POD_TOKEN_FILE" in runner.names("env"))
        assertFalse("projected" in spec.toString())
    }

    @Test
    fun anAuthenticatedReviewNamesTheBoundNamespaceAndPod() = runTest {
        api.on("POST", reviews, HttpStatusCode.Created to review())
        val identity = driver(true).review(CLUSTER, "pod.jwt.token", "ostrom")

        assertEquals(PodTokenIdentity("ostrom", "ostrom-s-x-e2"), identity)
        val call = api.calls.single()
        assertEquals("POST $reviews", "${call.method} ${call.path}")
        assertEquals("Bearer sa", call.authorization)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("TokenReview", body["kind"]!!.jsonPrimitive.content)
        assertEquals("authentication.k8s.io/v1", body["apiVersion"]!!.jsonPrimitive.content)
        val reviewed = body["spec"]!!.jsonObject
        assertEquals("pod.jwt.token", reviewed["token"]!!.jsonPrimitive.content)
        assertEquals(listOf("ostrom"), reviewed["audiences"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun aReviewThatDoesNotAuthenticateTheTokenForTheAudienceNamesNothing() = runTest {
        val driver = driver(true)
        for (answer in listOf(
            review(audiences = """["kubernetes"]"""),
            review(audiences = null),
            review(authenticated = false),
            review(username = "kubernetes-admin"),
            review(username = "user:serviceaccount:ostrom:default"),
            review(username = "system:node:ostrom:default"),
            """{"status":{"authenticated":true,"audiences":["ostrom"]}}""",
            """{"status":{"authenticated":true,"audiences":["ostrom"],"user":{}}}""",
            """{"status":{}}""",
            """{}""",
        )) {
            api.on("POST", reviews, HttpStatusCode.Created to answer)
            assertNull(driver.review(CLUSTER, "pod.jwt.token", "ostrom"), answer)
        }
        // Bound to no single pod: the review names none.
        val extras = listOf(null, "{}", """{"authentication.kubernetes.io/pod-name":["a","b"]}""",
            """{"authentication.kubernetes.io/pod-name":[{}]}""")
        for (extra in extras) {
            api.on("POST", reviews, HttpStatusCode.Created to review(extra = extra))
            assertEquals(PodTokenIdentity("ostrom", null), driver.review(CLUSTER, "pod.jwt.token", "ostrom"), extra)
        }
        api.on("POST", reviews, HttpStatusCode.Created to review(audiences = """[{},"ostrom"]"""))
        assertEquals(PodTokenIdentity("ostrom", "ostrom-s-x-e2"), driver.review(CLUSTER, "pod.jwt.token", "ostrom"))
    }

    @Test
    fun aForbiddenReviewFailsWithoutTheApisBody() = runTest {
        api.on("POST", reviews, HttpStatusCode.Forbidden to """{"message":"secret detail"}""")
        val failure =
            assertFailsWith<KubernetesApiException> { driver(true).review(CLUSTER, "pod.jwt.token", "ostrom") }
        assertEquals("Kubernetes API: review token answered 403.", failure.message)
        api.down = true
        assertFailsWith<RuntimeUnreachableException> { driver(true).review(CLUSTER, "pod.jwt.token", "ostrom") }
    }

    private fun review(
        authenticated: Boolean = true,
        audiences: String? = """["ostrom"]""",
        username: String = "system:serviceaccount:ostrom:default",
        extra: String? = """{"authentication.kubernetes.io/pod-name":["ostrom-s-x-e2"]}""",
    ): String {
        val extra = extra?.let { ""","extra":$it""" }.orEmpty()
        val audience = audiences?.let { ""","audiences":$it""" }.orEmpty()
        return """{"status":{"authenticated":$authenticated$audience,"user":{"username":"$username"$extra}}}"""
    }
}

private const val CLUSTER = "c1"
