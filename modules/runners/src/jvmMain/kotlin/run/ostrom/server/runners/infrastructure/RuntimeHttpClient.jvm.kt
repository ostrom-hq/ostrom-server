// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The runtime's client (a Kubernetes API, a Docker daemon): with [caPem], it trusts only the certificates of that PEM
 * bundle (the cluster's CA, as the console stores it or the server pod's `ca.crt`).
 */
internal actual fun runtimeHttpClient(caPem: String?, timeoutMillis: Long): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    if (caPem != null) {
        val trust = trustingOnly(caPem)
        engine { https { trustManager = trust } }
    }
    install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
}

/**
 * A trust manager that accepts only the certificates of [bundle].
 *
 * @param bundle A PEM bundle of one or more certificates.
 *
 * @return The trust manager.
 *
 * @throws IllegalArgumentException When the bundle holds no certificate, or one that does not parse.
 */
internal fun trustingOnly(bundle: String): X509TrustManager {
    val certificates = try {
        bundle.byteInputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
    } catch (_: CertificateException) {
        throw IllegalArgumentException("The CA bundle does not parse.")
    }
    require(certificates.isNotEmpty()) { "The CA bundle holds no certificate." }
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca-$index", certificate) }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}
