package com.skeler.scanely.core.common

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Listeners run on the thread that completes the task rather than the main looper. The interceptor
 * blocks an OkHttp thread on this, so a main-thread callback made every token wait on the UI
 * thread being free.
 */
private val onCompletingThread = Executor { it.run() }

/**
 * Suspends until a Play Services task completes, or throws [onTimeout]'s exception after
 * [timeoutMs].
 *
 * Play Services can leave a task pending forever — a token refresh, a sign-in — and nothing else
 * bounds the wait: OkHttp's callTimeout cannot interrupt a thread blocked here, and a spinner on a
 * sign-in button has no timeout of its own. The timeout surfaces as an ordinary exception, never a
 * CancellationException, so callers that rethrow cancellation still get to show a message.
 */
suspend fun <T> Task<T>.awaitWithin(timeoutMs: Long, onTimeout: () -> Exception): T =
    try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                addOnSuccessListener(onCompletingThread) { cont.resume(it) }
                addOnFailureListener(onCompletingThread) { cont.resumeWithException(it) }
                addOnCanceledListener(onCompletingThread) { cont.cancel() }
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw onTimeout()
    }
