package com.skeler.scanely.auth.domain.model

data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')
            ?: "Account"
}
