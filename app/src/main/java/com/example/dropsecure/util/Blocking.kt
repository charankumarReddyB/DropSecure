package com.example.dropsecure.util

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs a blocking call (socket accept/connect) on its own thread so that coroutine cancellation
 * can unblock it: [onCancel] must close the underlying socket.
 */
suspend fun <T : Any> blockingCancellable(onCancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { cont ->
        val t = Thread {
            try {
                val v = block()
                if (cont.isActive) cont.resume(v) else (v as? Closeable)?.close()
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
        t.isDaemon = true
        t.name = "dropsecure-blocking"
        t.start()
        cont.invokeOnCancellation { try { onCancel() } catch (_: Throwable) {} }
    }
