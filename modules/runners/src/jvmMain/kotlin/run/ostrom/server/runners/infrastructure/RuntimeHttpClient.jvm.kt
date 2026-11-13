// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The runtime's client (the Kubernetes API, a Docker daemon over TLS): with [caFile], it trusts only the certificates
 * of that PEM bundle (the service account's `ca.crt`), as Curl's `caInfo` did on Kotlin/Native.
 */
internal actual fun runtimeHttpClient(caFile: String?, timeoutMillis: Long): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    if (caFile != null) {
        val trust = trustingOnly(File(caFile))
        engine { https { trustManager = trust } }
    }
    install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
}

/**
 * A trust manager that accepts only the certificates of [bundle].
 *
 * @param bundle A PEM file of one or more certificates.
 *
 * @return The trust manager.
 *
 * @throws IllegalArgumentException When the file holds no certificate.
 */
internal fun trustingOnly(bundle: File): X509TrustManager {
    val certificates = bundle.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
    require(certificates.isNotEmpty()) { "The CA bundle ${bundle.path} holds no certificate." }
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("ca-$index", certificate) }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}
