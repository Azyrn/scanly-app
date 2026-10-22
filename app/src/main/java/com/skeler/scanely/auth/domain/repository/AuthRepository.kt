package com.skeler.scanely.auth.domain.repository

import android.content.Context
import com.skeler.scanely.auth.domain.model.AuthUser
import kotlinx.coroutines.flow.StateFlow

/** Authentication required only for AI features. */
interface AuthRepository {

    val currentUser: StateFlow<AuthUser?>

    val isSignedIn: Boolean

    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser>

    suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser>

    /** The caller supplies the Activity context needed to show Credential Manager. */
    suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser>

    suspend fun sendPasswordReset(email: String): Result<Unit>

    /**
     * Mails a confirmation link for the signed-in address, through Firebase's own sender.
     *
     * Unlike [sendPasswordReset] this does not go via the backend: password reset needs a link to
     * our own page, which Firebase refuses to configure, while confirmation only has to be clicked.
     */
    suspend fun sendEmailVerification(): Result<Unit>

    /**
     * Re-reads the account from Firebase and forces a fresh ID token.
     *
     * `email_verified` is a claim inside the token, so a link clicked in a browser changes nothing
     * the server can see until the cached token is thrown away.
     */
    suspend fun refreshSession(): Result<Unit>

    suspend fun deleteAccount(activityContext: Context): Result<Unit>

    suspend fun signOut(activityContext: Context)
}
