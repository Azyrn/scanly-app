package com.skeler.scanely.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Backend routes reachable without a signed-in user. Its client carries no credential at all — no
 * `Authorization` header — so it must never be given a route that reads a uid.
 */
interface ScanlyPublicApi {

    /**
     * Asks the Worker to mail a password-reset link.
     *
     * Firebase's own `sendPasswordResetEmail` is not used: the project's email templates cannot
     * be pointed at scanlyocr.com, so Firebase would mail a link to a handler that does not
     * exist. The Worker mints the same one-time code and sends the mail itself.
     */
    @POST("v1/auth/password-reset")
    suspend fun sendPasswordReset(@Body request: PasswordResetRequest)

    /** Which AI features the server has switched off. Public so Home can hide them before sign-in. */
    @GET("v1/features")
    suspend fun features(): FeaturesResponse

    /**
     * A crash or conversion-failure report (core/reports/FailureReporter). Public: a crash can come
     * before sign-in. Carries fixed fields only — never document content or file names.
     */
    @POST("v1/reports")
    suspend fun report(@Body report: AppReportDto): retrofit2.Response<Unit>
}

@Serializable
data class AppReportDto(
    val kind: String,
    val direction: String? = null,
    val error: String,
    val where: String? = null,
    val durationMs: Long? = null,
    val pages: Int? = null,
    val appVersion: String,
    val versionCode: Int,
    val sdk: Int
)

/** Keyed by the lowercase `AiFeature` name; a feature the server does not mention stays on. */
@Serializable
data class FeaturesResponse(val features: Map<String, Boolean> = emptyMap())

@Serializable
data class PasswordResetRequest(val email: String)
