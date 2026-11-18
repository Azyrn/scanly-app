package com.skeler.scanely.core.ai

import com.skeler.scanely.core.billing.EntitlementRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The question PDF to Word asks before it reads difficult pages online: "N pages need online
 * reading. Cost: N credits." — Use credits or Convert without. Choosing to use credits is the
 * consent; nothing is sent without it. One conversion runs at a time, so one question at a time.
 *
 * The answer comes from the tool screen; with too few credits it can first watch an ad or open
 * the plans, then answer with the new balance ([creditsLeft] follows /v1/me).
 */
@Singleton
class OnlineReadingPrompt @Inject constructor(private val entitlements: EntitlementRepository) {
    /** Pages waiting to be read online, or null when nothing is being asked. */
    private val _pages = MutableStateFlow<Int?>(null)
    val pages: StateFlow<Int?> = _pages.asStateFlow()

    @Volatile
    private var answer: CompletableDeferred<Boolean>? = null

    /** Credits usable now: the smaller of what is left this month and today, or null if unknown. */
    val creditsLeft: Flow<Int?> =
        entitlements.entitlement.map { me -> me?.let { minOf(it.pagesRemainingMonth, it.pagesRemainingDay) } }

    /** True to read [count] pages online for [count] credits; false to convert with the PDF's own text. */
    suspend fun ask(count: Int): Boolean {
        refreshBalance()
        withTimeoutOrNull(BALANCE_WAIT_MS) { entitlements.status.first { !it.isRefreshing } }
        val pending = CompletableDeferred<Boolean>().also { answer = it }
        _pages.value = count
        return try {
            pending.await()
        } finally {
            _pages.value = null
            answer = null
        }
    }

    private val _failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A read the user agreed to pay for came back with nothing; the tool screen says so. */
    val failures: SharedFlow<Unit> = _failures.asSharedFlow()

    /** After "Use credits", when no page was read: nothing is billed for it (not sent, or given back). */
    fun reportFailure() {
        _failures.tryEmit(Unit)
    }

    fun useCredits() {
        answer?.complete(true)
    }

    fun convertWithout() {
        answer?.complete(false)
    }

    /** After an ad or a purchase, so the question shows the new balance. */
    fun refreshBalance() = entitlements.refresh()

    private companion object {
        const val BALANCE_WAIT_MS = 3_000L
    }
}
