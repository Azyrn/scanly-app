package com.skeler.scanely.core.network

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.GetTokenResult
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Play Services can stall a token refresh indefinitely. The interceptor blocks an OkHttp thread on
 * it, and a callTimeout cannot interrupt that wait, so without a deadline of its own the request —
 * a plan refresh, a licence check — spins forever.
 *
 * The task here is real and simply never completes, which is exactly what a stall looks like.
 */
class TokenWaitDeadlineTest {

    @Test(timeout = 60_000)
    fun `a token fetch that never completes gives up with a message for the user`() {
        val stalled = TaskCompletionSource<GetTokenResult>().task
        val started = System.currentTimeMillis()

        try {
            awaitToken("Could not verify your session.") { stalled }
            fail("a stalled token fetch returned")
        } catch (e: IOException) {
            val waited = System.currentTimeMillis() - started
            assertTrue("gave up within the deadline, not the test's: $waited ms", waited < 30_000)
            assertTrue(e.message!!, e.message!!.contains("try again", ignoreCase = true))
        }
    }
}
