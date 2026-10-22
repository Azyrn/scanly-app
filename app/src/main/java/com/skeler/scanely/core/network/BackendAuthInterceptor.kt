package com.skeler.scanely.core.network

import com.google.firebase.auth.FirebaseAuth
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches `Authorization: Bearer <firebase-id-token>` — who the user is. Quota is keyed on it.
 *
 * Firebase caches the token and refreshes it itself, so the common case is a cheap in-memory read.
 * Blocking for it is correct rather than lazy: OkHttp interceptors are synchronous by contract,
 * and this runs on OkHttp's own background dispatcher, never the main thread.
 */
@Singleton
class BackendAuthInterceptor @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val user = firebaseAuth.currentUser
            ?: throw TokenUnavailableException("Sign in to use AI scanning.")

        return chain.proceedWithToken { forceRefresh ->
            if (firebaseAuth.currentUser?.uid != user.uid) {
                throw TokenUnavailableException("Your account changed. Please try again.")
            }
            awaitToken("Could not verify your session.") { user.getIdToken(forceRefresh) }
                .token.orEmpty()
        }
    }
}

internal fun Interceptor.Chain.proceedWithToken(token: (Boolean) -> String): Response {
    fun authorized(forceRefresh: Boolean): okhttp3.Request {
        val idToken = token(forceRefresh)
        if (idToken.isEmpty()) throw TokenUnavailableException("Your session expired. Sign in again.")
        return request().newBuilder().header("Authorization", "Bearer $idToken").build()
    }
    val response = proceed(authorized(false))
    if (response.code != 401) return response
    response.close()
    // Authentication is checked before any billing. Retry once with a forced refresh.
    return proceed(authorized(true))
}
