package com.skeler.scanely.auth.data.repository

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.skeler.scanely.R
import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.common.awaitWithin
import com.skeler.scanely.core.network.BackendErrorEnvelope
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.core.network.PasswordResetRequest
import com.skeler.scanely.core.network.ScanlyApi
import com.skeler.scanely.core.network.ScanlyPublicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FirebaseAuthRepository"

class AuthException(message: String) : Exception(message)

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val credentialManager: CredentialManager,
    private val publicApi: ScanlyPublicApi,
    private val api: ScanlyApi,
    private val networkObserver: NetworkObserver
) : AuthRepository {

    private val _currentUser = MutableStateFlow(firebaseAuth.currentUser?.toAuthUser())
    override val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()

    override val isSignedIn: Boolean
        get() = firebaseAuth.currentUser != null

    init {
        // Also captures external sign-out, token revocation, and account deletion.
        firebaseAuth.addAuthStateListener { auth ->
            _currentUser.value = auth.currentUser?.toAuthUser()
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
        runAuthCatching {
            firebaseAuth.signInWithEmailAndPassword(email.trim(), password)
                .await()
                .requireUser()
        }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AuthUser> =
        runAuthCatching {
            firebaseAuth.createUserWithEmailAndPassword(email.trim(), password)
                .await()
                .requireUser()
        }

    override suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser> =
        runAuthCatching {
            val googleOption = GetSignInWithGoogleOption.Builder(
                activityContext.getString(R.string.default_web_client_id)
            ).build()

            val response = credentialManager.getCredential(
                context = activityContext,
                request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
            )

            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw AuthException("Unexpected credential type from Google.")
            }

            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await()
                .requireUser()
        }

    /** Uses the backend because Firebase email templates cannot target the hosted reset page. */
    override suspend fun sendPasswordReset(email: String): Result<Unit> =
        runAuthCatching {
            publicApi.sendPasswordReset(PasswordResetRequest(email.trim()))
        }

    override suspend fun sendEmailVerification(): Result<Unit> =
        runAuthCatching {
            val user = firebaseAuth.currentUser
                ?: throw AuthException("Sign in first, then confirm your email.")
            user.sendEmailVerification().await()
        }

    override suspend fun refreshSession(): Result<Unit> =
        runAuthCatching {
            val user = firebaseAuth.currentUser
                ?: throw AuthException("Sign in first.")
            user.reload().await()
            // reload() updates the account, not the cached token that carries `email_verified`.
            firebaseAuth.currentUser?.getIdToken(true)?.await()
            _currentUser.value = firebaseAuth.currentUser?.toAuthUser()
        }

    /** Play requires an in-app way to delete the account, not just an address to write to. */
    override suspend fun deleteAccount(activityContext: Context): Result<Unit> =
        runAuthCatching {
            api.deleteAccount()
            signOut(activityContext)
        }

    override suspend fun signOut(activityContext: Context) {
        firebaseAuth.signOut()
        // Prevent the next sign-in from silently reusing the previous Google account.
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Failed to clear credential state", e)
        }
    }

    private inline fun <T> runAuthCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Auth call failed", e)
            Result.failure(AuthException(e.toUserMessage()))
        }

    private fun Exception.toUserMessage(): String = when (this) {
        is AuthException -> message.orEmpty()
        is GetCredentialCancellationException -> "Sign-in cancelled."
        is NoCredentialException ->
            "No Google account available on this device. Add one in Settings, then try again."
        is GoogleIdTokenParsingException -> "Google returned a malformed token. Try again."
        is GetCredentialException -> "Google sign-in is unavailable right now. Try again."
        is FirebaseAuthWeakPasswordException ->
            reason ?: "Password is too weak — use at least 6 characters."
        is FirebaseAuthUserCollisionException ->
            "An account already exists for that email. Sign in instead."
        // Preserve Firebase's account-enumeration protection.
        is FirebaseAuthInvalidCredentialsException,
        is FirebaseAuthInvalidUserException -> "Incorrect email or password."
        is FirebaseNetworkException -> "No internet connection."
        is FirebaseTooManyRequestsException -> "Too many attempts. Try again later."
        // Prefer the backend's user-facing error message.
        is HttpException -> backendMessage() ?: "Something went wrong. Try again."
        // An IOException is anything below HTTP: refused, reset, TLS, DNS, timeout. Only the
        // device knows whether that is because it is offline.
        is IOException ->
            if (networkObserver.isCurrentlyOnline()) SERVER_UNREACHABLE else "No internet connection."
        else -> message ?: "Something went wrong. Try again."
    }

    private fun HttpException.backendMessage(): String? = try {
        backendJson.decodeFromString<BackendErrorEnvelope>(
            response()?.errorBody()?.string().orEmpty()
        ).error?.message
    } catch (_: Exception) {
        null
    }

    private companion object {
        val backendJson = Json { ignoreUnknownKeys = true }
        const val SERVER_UNREACHABLE =
            "Couldn't reach the Scanly server. Your connection looks fine — try again in a few minutes."
    }
}

private fun com.google.firebase.auth.AuthResult.requireUser(): AuthUser =
    user?.toAuthUser() ?: throw AuthException("Sign-in succeeded but returned no account.")

private fun FirebaseUser.toAuthUser() = AuthUser(
    uid = uid,
    email = email,
    displayName = displayName,
    photoUrl = photoUrl?.toString()
)

/**
 * Every Firebase call here is one round trip; thirty seconds is a stalled Play Services task, and
 * without a deadline the sign-in button spun with no way out.
 */
private const val AUTH_WAIT_MS = 30_000L

private suspend fun <T> Task<T>.await(): T = awaitWithin(AUTH_WAIT_MS) {
    AuthException("Signing in is taking too long. Check your connection and try again.")
}
