package com.skeler.scanely.core.ai

import android.content.Context
import android.util.Log
import com.skeler.scanely.core.di.BackendControl
import com.skeler.scanely.core.network.ConversionRefundRequest
import com.skeler.scanely.core.network.ScanlyApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ConversionCharges"

/**
 * How long a refund is worth asking for. The server honours one for `CONVERSION_REFUND_WINDOW_MS`
 * (worker/src/conversion-refunds.ts) after the read was billed and answers `expired` after that.
 * Counted here from the first ask: a read whose answer was lost was billed before it, so the server
 * refuses it about then; a read still running at the first ask is billed within minutes, and the
 * asks every [RETRY_DELAY_MS] reach it long before this is over.
 */
internal const val REFUND_WINDOW_MS = 30 * 60_000L

/** Wait between asks for a kept refund while the app runs: a read still running on the server finishes within minutes. */
internal const val RETRY_DELAY_MS = 2 * 60_000L

/** HTTP answers that may change if asked again: a timeout, rate limit, server error, or a session being renewed. */
private val RETRYABLE_HTTP = setOf(401, 408, 429)
private const val HTTP_SERVER_ERROR = 500

/** The server's answer for a key it has no billed read for: never existed, or still running there. */
private const val REASON_NOT_FOUND = "not_found"

private const val PENDING_FILE = "pending-conversion-refunds"

/** A refund the server has not answered for good yet, first asked for at [firstAt]. */
private data class PendingRefund(val key: String, val firstAt: Long)

/**
 * The billed cloud reads of the conversion running now (PDF to Word's OCR fallback), so a
 * conversion that fails after paying for them can ask for the credits back. One tool job runs at
 * a time, so one list is enough: the tool flow clears it before a run and after a good result.
 *
 * The server decides what comes back (routes/scan.ts `handleConversionRefund`); this only names
 * the reads, and the server refunds a read at most once (`already_refunded`), so asking again is
 * always safe. A refund the server could not answer for good — offline, a server error, or
 * `not_found`, which is also its answer for a read still running there — is kept on disk and asked
 * again every [RETRY_DELAY_MS] while the app runs, at the next tool run and at the next app start,
 * until the server answers or [REFUND_WINDOW_MS] is over.
 */
@Singleton
class ConversionCharges internal constructor(
    private val api: ScanlyApi,
    private val pendingFile: File,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher
) {
    // The control client (20 s per call): a refund is small JSON, and on a dead connection the scan
    // client would hold it for its 300 s read timeout.
    @Inject constructor(@BackendControl api: ScanlyApi, @ApplicationContext context: Context) :
        this(api, File(context.filesDir, PENDING_FILE), System::currentTimeMillis, Dispatchers.IO)

    private val keys = ConcurrentLinkedQueue<String>()
    private val pendingLock = Mutex()
    private var retries: Job? = null

    /** Outlives the screen: a user who leaves the failed conversion still gets the refund. */
    val scope = CoroutineScope(SupervisorJob() + io)

    init {
        // Refunds an earlier run of the app could not get answered; those still deferred are asked
        // again while this run lasts, as they would have been in the run that kept them.
        scope.launch {
            retryPending()
            if (pendingLock.withLock { readPending() }.isNotEmpty()) scheduleRetries()
        }
    }

    fun record(idempotencyKey: String) {
        keys.add(idempotencyKey)
    }

    /** Before and after a tool run; also a good time to ask again for kept refunds. */
    fun clear() {
        keys.clear()
        scope.launch { retryPending() }
    }

    /** Asks for every recorded read back; the pages the server returned now. */
    suspend fun refundAll(): Int = withContext(NonCancellable + io) {
        var pages = 0
        while (true) {
            val key = keys.poll() ?: break
            pages += refund(key)
        }
        pages
    }

    /** One read back, unusable or given up on; the pages the server returned now, 0 when the answer is deferred. */
    suspend fun refund(key: String): Int = withContext(NonCancellable + io) {
        keys.remove(key)
        // On disk before it is sent: a call on a dead connection can hang for minutes, and a kill
        // meanwhile must not take the user's credits with it.
        keep(PendingRefund(key, clock()))
        when (val outcome = send(key)) {
            is Outcome.Refunded -> {
                drop(key)
                outcome.pages
            }
            Outcome.Final -> {
                drop(key)
                0
            }
            Outcome.Later -> {
                scheduleRetries()
                0
            }
        }
    }

    /** Asks again while anything is kept; the kept refunds end by [REFUND_WINDOW_MS], and so does this. */
    private fun scheduleRetries() = synchronized(this) {
        if (retries?.isActive == true) return@synchronized
        retries = scope.launch {
            do {
                delay(RETRY_DELAY_MS)
                retryPending()
            } while (pendingLock.withLock { readPending() }.isNotEmpty())
        }
    }

    /** Asks again for every kept refund; keeps only those still deferred and inside the window. */
    private suspend fun retryPending() = withContext(NonCancellable + io) {
        pendingLock.withLock {
            val left = readPending().filter { pending ->
                clock() - pending.firstAt <= REFUND_WINDOW_MS && send(pending.key) == Outcome.Later
            }
            writePending(left)
        }
    }

    private sealed interface Outcome {
        data class Refunded(val pages: Int) : Outcome
        data object Final : Outcome
        data object Later : Outcome
    }

    private suspend fun send(key: String): Outcome = try {
        val response = api.refundConversion(ConversionRefundRequest(key))
        when {
            response.refundedPages > 0 -> Outcome.Refunded(response.refundedPages)
            response.reason == REASON_NOT_FOUND -> Outcome.Later
            // already_refunded, expired, refund_limit: the server's last word.
            else -> Outcome.Final
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        Log.w(TAG, "Refund refused: HTTP ${e.code()}")
        httpOutcome(e.code())
    } catch (e: IOException) {
        Log.w(TAG, "Refund not sent: ${e.javaClass.simpleName}")
        Outcome.Later
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // An answer that did not parse (a captive portal's page): bounded by the window like the rest.
        Log.w(TAG, "Refund not answered: ${e.javaClass.simpleName}")
        Outcome.Later
    }

    /** Kept once: a key asked for again keeps its first ask's time, so its window does not restart. */
    private fun httpOutcome(code: Int): Outcome =
        if (code >= HTTP_SERVER_ERROR || code in RETRYABLE_HTTP) Outcome.Later else Outcome.Final

    private suspend fun keep(refund: PendingRefund) = pendingLock.withLock {
        val pending = readPending()
        if (pending.none { it.key == refund.key }) writePending(pending + refund)
    }

    private suspend fun drop(key: String) = pendingLock.withLock {
        writePending(readPending().filterNot { it.key == key })
    }

    private fun readPending(): List<PendingRefund> = try {
        if (!pendingFile.exists()) {
            emptyList()
        } else {
            pendingFile.readLines().mapNotNull { line ->
                val key = line.substringBefore('\t')
                val firstAt = line.substringAfter('\t', "").toLongOrNull()
                if (key.isNotBlank() && firstAt != null) PendingRefund(key, firstAt) else null
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Pending refunds not read: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** Written whole to a temporary file and renamed over the old one, so a kill mid-write keeps the old list. */
    private fun writePending(pending: List<PendingRefund>) {
        try {
            if (pending.isEmpty()) {
                pendingFile.delete()
                return
            }
            val tmp = File(pendingFile.parentFile, "${pendingFile.name}.tmp")
            tmp.writeText(pending.joinToString("\n") { "${it.key}\t${it.firstAt}" })
            if (!tmp.renameTo(pendingFile)) Log.w(TAG, "Pending refunds not saved")
        } catch (e: IOException) {
            // A full disk: the refund is lost as before this list existed, never the conversion with it.
            Log.w(TAG, "Pending refunds not saved: ${e.javaClass.simpleName}")
        }
    }
}
