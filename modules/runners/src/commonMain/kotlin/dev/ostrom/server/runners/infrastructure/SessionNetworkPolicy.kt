package dev.ostrom.server.runners.infrastructure

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The NetworkPolicies of the session pods, from TeamCode's `teamcode-sessions` policy: no ingress at all; egress to
 * DNS, to the Ostrom server pods, and to public addresses only (private, carrier-grade NAT, loopback, link-local
 * and metadata, multicast and reserved ranges are excepted, so an agent can reach neither the cluster, the nodes,
 * the API server on a private address, nor a cloud metadata service).
 *
 * The free plan's pods get the restricted variant: the internet on web and Git ports only (80, 443, 22, 9418),
 * plus a bandwidth annotation on the pod.
 */
object SessionNetworkPolicy {
    /** DNS, over UDP and TCP. */
    private const val DNS_PORT = 53

    /** The label of the server's pods, which the runners may reach (set by the deployment). */
    const val SERVER_POD_LABEL = "app.kubernetes.io/name"
    const val SERVER_POD_NAME = "ostrom-server"
    const val SERVER_PORT = 8080

    /** IPv4 ranges never reachable from a session. */
    val BLOCKED_V4 = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
        "224.0.0.0/4", "240.0.0.0/4", "168.63.129.16/32",
    )

    /**
     * IPv6 ranges never reachable from a session (unique-local holds AWS's IPv6 metadata, NAT64 and 6to4 embed
     * IPv4).
     */
    val BLOCKED_V6 = listOf(
        "::1/128", "fc00::/7", "fe80::/10", "ff00::/8", "64:ff9b::/96", "64:ff9b:1::/48", "2002::/16", "2001::/32",
    )

    /** The ports a restricted session may reach on the internet: HTTP, HTTPS, SSH and the Git protocol. */
    val RESTRICTED_PORTS = listOf(80, 443, 22, 9418)

    /**
     * Builds one policy.
     *
     * @param name The policy's name.
     * @param restricted Whether it is the free plan's variant.
     *
     * @return The NetworkPolicy manifest.
     */
    fun manifest(name: String, restricted: Boolean): JsonObject = buildJsonObject {
        put("apiVersion", "networking.k8s.io/v1")
        put("kind", "NetworkPolicy")
        putJsonObject("metadata") {
            put("name", name)
            putJsonObject("labels") {
                put(KubernetesRuntimeDriver.MANAGED_BY_LABEL, KubernetesRuntimeDriver.MANAGED_BY)
            }
        }
        putJsonObject("spec") {
            putJsonObject("podSelector") {
                putJsonObject("matchLabels") {
                    put(KubernetesRuntimeDriver.MANAGED_BY_LABEL, KubernetesRuntimeDriver.MANAGED_BY)
                    put(KubernetesRuntimeDriver.EGRESS_LABEL, if (restricted) "restricted" else "open")
                }
            }
            putJsonArray("policyTypes") {
                add(JsonPrimitive("Ingress"))
                add(JsonPrimitive("Egress"))
            }
            putJsonArray("ingress") {}
            putJsonArray("egress") {
                add(
                    buildJsonObject {
                        putJsonArray("ports") {
                            add(port("UDP", DNS_PORT))
                            add(port("TCP", DNS_PORT))
                        }
                    },
                )
                add(
                    buildJsonObject {
                        putJsonArray("to") {
                            add(
                                buildJsonObject {
                                    putJsonObject("podSelector") {
                                        putJsonObject("matchLabels") { put(SERVER_POD_LABEL, SERVER_POD_NAME) }
                                    }
                                },
                            )
                        }
                        putJsonArray("ports") { add(port("TCP", SERVER_PORT)) }
                    },
                )
                add(
                    buildJsonObject {
                        putJsonArray("to") {
                            add(ipBlock("0.0.0.0/0", BLOCKED_V4))
                            add(ipBlock("::/0", BLOCKED_V6))
                        }
                        if (restricted) putJsonArray("ports") { RESTRICTED_PORTS.forEach { add(port("TCP", it)) } }
                    },
                )
            }
        }
    }

    private fun port(protocol: String, port: Int) = buildJsonObject {
        put("protocol", protocol)
        put("port", port)
    }

    private fun ipBlock(cidr: String, except: List<String>) = buildJsonObject {
        putJsonObject("ipBlock") {
            put("cidr", cidr)
            putJsonArray("except") { except.forEach { add(JsonPrimitive(it)) } }
        }
    }
}
