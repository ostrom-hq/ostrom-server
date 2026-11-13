// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.cert.X509Certificate
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The runtime's client (the Kubernetes API, a Docker daemon over TLS): with [caFile], it trusts only the certificates
 * of that PEM bundle (the service account's `ca.crt`), read at the first TLS handshake: a configuration names the file
 * before it exists outside a pod, and a missing file fails the call, never the start.
 */
internal actual fun runtimeHttpClient(caFile: String?, timeoutMillis: Long): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    if (caFile != null) {
        val trust = BundleTrustManager(File(caFile))
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

/** Trusts only the certificates of [bundle] ([trustingOnly]), read when a handshake first needs them. */
internal class BundleTrustManager(private val bundle: File) : X509TrustManager {
    private val delegate by lazy { trustingOnly(bundle) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkServerTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
