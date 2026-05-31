package com.uow.scan.util

import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded system-resolver DNS lookup, shared by the DNS tools. [InetAddress.getAllByName] has no
 * timeout of its own, so each lookup runs on a daemon thread and is abandoned after [timeoutMs] —
 * a hung native lookup can never stall a scan or block process exit.
 *
 * Used by both [DnsHijackProbe] (Tier-A baseline comparison) and [DnsLeakProbe] (Tier-B egress
 * trigger), which previously each spun an identical cached daemon pool of their own.
 */
internal object SystemDnsLookup {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "dns-lookup").apply { isDaemon = true }
    }

    /** Every address [host] resolves to on the system resolver; empty on timeout or failure. */
    fun resolve(host: String, timeoutMs: Long): List<InetAddress> {
        val future = executor.submit(Callable {
            runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)   // abandon a hung lookup; the daemon thread can't block exit
            emptyList()
        }
    }
}
