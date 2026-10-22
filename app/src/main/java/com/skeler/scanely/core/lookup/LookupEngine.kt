package com.skeler.scanely.core.lookup

sealed interface LookupResult {
    data class Found(
        val product: ProductInfo,
        val source: String
    ) : LookupResult

    data class NotFound(val source: String) : LookupResult

    data class Error(
        val source: String,
        val exception: Exception
    ) : LookupResult
}

interface LookupEngine {
    val name: String

    /** Lower = higher priority in fallback order. */
    val priority: Int
    val category: ProductCategory

    fun supports(barcode: String): Boolean

    /**
     * [LookupResult.Found] or [LookupResult.NotFound] only. A failure — no connection, a timeout, a
     * 5xx — is thrown, not returned: [LookupOrchestrator] retries what may clear and tells the user
     * which of offline, server trouble or not found it was.
     */
    suspend fun lookup(barcode: String): LookupResult
}
