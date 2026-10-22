package com.skeler.scanely.core.billing

import com.skeler.scanely.auth.domain.model.AuthUser
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.network.MeResponse
import com.skeler.scanely.core.network.ScanlyApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class EntitlementRepositoryTest {
    @Test fun `an old account response cannot restore quota after sign out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val auth = mock(AuthRepository::class.java)
            val user = mock(AuthUser::class.java)
            `when`(user.uid).thenReturn("old-account")
            val users = MutableStateFlow<AuthUser?>(user)
            `when`(auth.currentUser).thenReturn(users)
            `when`(auth.isSignedIn).thenAnswer { users.value != null }
            val api = mock(ScanlyApi::class.java)
            var pending: Continuation<MeResponse>? = null
            doAnswer {
                @Suppress("UNCHECKED_CAST")
                pending = it.rawArguments.last() as Continuation<MeResponse>
                COROUTINE_SUSPENDED
            }.`when`(api).me()
            val repository = EntitlementRepository(api, auth, scope)
            assertNotNull(pending)
            users.value = null
            assertNull(repository.entitlement.value)
            pending!!.resume(mock(MeResponse::class.java))
            assertNull(repository.entitlement.value)
            assertFalse(repository.status.value.isRefreshing)
            assertNotNull(repository.status.value.error)
        } finally { scope.cancel() }
    }

    @Test fun `newer refresh wins when responses arrive out of order`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val auth = mock(AuthRepository::class.java)
            val user = mock(AuthUser::class.java)
            `when`(user.uid).thenReturn("account")
            `when`(auth.currentUser).thenReturn(MutableStateFlow(user))
            `when`(auth.isSignedIn).thenReturn(true)
            val api = mock(ScanlyApi::class.java)
            val pending = mutableListOf<Continuation<MeResponse>>()
            doAnswer {
                @Suppress("UNCHECKED_CAST")
                pending += it.rawArguments.last() as Continuation<MeResponse>
                COROUTINE_SUSPENDED
            }.`when`(api).me()
            val repository = EntitlementRepository(api, auth, scope)
            repository.refresh()
            val newer = mock(MeResponse::class.java)
            pending[1].resume(newer)
            pending[0].resume(mock(MeResponse::class.java))
            assertSame(newer, repository.entitlement.value)
        } finally { scope.cancel() }
    }
}
