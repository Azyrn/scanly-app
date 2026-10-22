package com.skeler.scanely.auth.presentation.viewmodel

import android.content.Context
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MIN_PASSWORD_LENGTH = 6

enum class AuthMode { SIGN_IN, SIGN_UP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null
) {
    val isEmailValid: Boolean
        get() = email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    val isPasswordValid: Boolean
        get() = password.length >= MIN_PASSWORD_LENGTH

    val canSubmit: Boolean
        get() = !isLoading && isEmailValid && isPasswordValid
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val currentUser: StateFlow<AuthUser?> = authRepository.currentUser

    fun onEmailChange(value: String) =
        _uiState.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, error = null) }

    fun toggleMode() = _uiState.update {
        it.copy(
            mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN,
            error = null,
            info = null
        )
    }

    fun consumeMessages() = _uiState.update { it.copy(error = null, info = null) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) {
            _uiState.update { it.copy(error = it.validationError()) }
            return
        }
        launchAuth {
            when (state.mode) {
                AuthMode.SIGN_IN -> authRepository.signInWithEmail(state.email, state.password)
                AuthMode.SIGN_UP -> authRepository.signUpWithEmail(state.email, state.password)
            }
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        launchAuth { authRepository.signInWithGoogle(activityContext) }
    }

    fun sendPasswordReset() {
        val state = _uiState.value
        if (!state.isEmailValid) {
            _uiState.update { it.copy(error = "Enter your email address first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, info = null) }
            val result = authRepository.sendPasswordReset(state.email)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message,
                    // Do not reveal whether this address has a password account.
                    info = (
                        "If ${state.email.trim()} has a password, a reset link is on its " +
                            "way. Google accounts have none — use Continue with Google."
                        )
                        .takeIf { _ -> result.isSuccess }
                )
            }
        }
    }

    fun signOut(activityContext: Context) {
        viewModelScope.launch { authRepository.signOut(activityContext) }
    }

    fun deleteAccount(activityContext: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, info = null) }
            val result = authRepository.deleteAccount(activityContext)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message,
                    info = if (result.isSuccess) "Your account has been deleted." else null
                )
            }
        }
    }

    private fun launchAuth(block: suspend () -> Result<AuthUser>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, info = null) }
            val result = block()
            _uiState.update {
                it.copy(isLoading = false, error = result.exceptionOrNull()?.message)
            }
        }
    }

    private fun AuthUiState.validationError(): String = when {
        !isEmailValid -> "Enter a valid email address."
        !isPasswordValid -> "Password must be at least $MIN_PASSWORD_LENGTH characters."
        else -> "Check your details and try again."
    }
}
