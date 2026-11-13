// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.server.kernel.network

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.IOException
import platform.posix.AF_UNSPEC
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.memset

private const val TEXT_BYTES = 64

internal actual val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

/** `getaddrinfo`, the resolver of the C library, so `/etc/hosts`, search domains and the cluster's DNS keep working. */
internal actual fun systemLookup(host: String): List<String> = memScoped {
    val hints = alloc<addrinfo>()
    memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    val found = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, null, hints.ptr, found.ptr) != 0) throw IOException("The host could not be resolved.")
    val text = allocArray<ByteVar>(TEXT_BYTES)
    try {
        generateSequence(found.value) { it.pointed.ai_next }.mapNotNull { entry ->
            val address = entry.pointed
            val status = getnameinfo(
                address.ai_addr, address.ai_addrlen, text, TEXT_BYTES.convert(), null, 0.convert(), NI_NUMERICHOST,
            )
            if (status == 0) text.toKString() else null
        }.toList()
    } finally {
        freeaddrinfo(found.value)
    }
}
