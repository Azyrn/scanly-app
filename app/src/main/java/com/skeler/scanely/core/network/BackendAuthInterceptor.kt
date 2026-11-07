package com.skeler.scanely.core.network

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.skeler.scanely.R
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val context: Context,
    private val firebaseAuth: FirebaseAuth
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val user = firebaseAuth.currentUser
            ?: throw TokenUnavailableException(context.getString(R.string.ai_err_sign_in))

        return chain.proceedWithToken(context) { forceRefresh ->
            if (firebaseAuth.currentUser?.uid != user.uid) {
                throw TokenUnavailableException(context.getString(R.string.net_account_changed))
            }
            awaitToken(context, context.getString(R.string.net_verify_session)) { user.getIdToken(forceRefresh) }
                .token.orEmpty()
        }
    }
}

internal fun Interceptor.Chain.proceedWithToken(context: Context, token: (Boolean) -> String): Response {
    fun authorized(forceRefresh: Boolean): okhttp3.Request {
        val idToken = token(forceRefresh)
        if (idToken.isEmpty()) throw TokenUnavailableException(context.getString(R.string.net_session_expired))
        return request().newBuilder().header("Authorization", "Bearer $idToken").build()
    }
    val response = proceed(authorized(false))
    if (response.code != HTTP_UNAUTHORIZED) return response
    response.close()
    // Authentication is checked before any billing. Retry once with a forced refresh.
    return proceed(authorized(true))
}

private const val HTTP_UNAUTHORIZED = 401
