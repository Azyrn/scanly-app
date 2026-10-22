package com.skeler.scanely.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.withResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.ComposeNavigator
import kotlinx.coroutines.flow.StateFlow

/**
 * The only way a screen may move the back stack.
 *
 * Each instance is bound to the entry of the screen that reads it, and every call is dropped
 * unless that entry is still the top of the stack. That single rule is what makes navigation
 * single-shot: the second of a double tap, a tap that lands after a transition has already
 * started, and a tap delivered to a screen that has been popped all find that the stack has moved
 * on, and do nothing. Screens therefore need no re-entrancy flags of their own.
 *
 * The check is deliberately "is top", not "is RESUMED". Activity results — every picker in this
 * app — are dispatched before the host reaches RESUMED, so a lifecycle-based guard would swallow
 * exactly the navigations that carry the user's chosen file into the app.
 */
class Navigator internal constructor(
    private val controller: NavController,
    private val entry: NavBackStackEntry
) {
    internal val screenLifecycle: Lifecycle get() = entry.lifecycle

    /** False once this screen has been popped, including while its exit transition still runs. */
    internal val isOnBackStack: Boolean
        get() = controller.navigatorProvider
            .getNavigator<ComposeNavigator>("composable")
            .backStack.value.any { it.id == entry.id }

    private val isTop: Boolean
        get() = controller.currentBackStackEntry?.id == entry.id

    fun go(destination: Destination) {
        if (isTop) controller.navigate(destination)
    }

    fun back() {
        if (isTop) controller.popBackStack()
    }

    /** Goes back and hands [value] to the screen underneath, which reads it with [result]. */
    fun backWithResult(key: String, value: String) {
        if (!isTop) return
        controller.previousBackStackEntry?.savedStateHandle?.set(key, value)
        controller.popBackStack()
    }

    /** A value a screen above this one left on the way back; null once [clearResult] took it. */
    fun <T> result(key: String): StateFlow<T?> = entry.savedStateHandle.getStateFlow(key, null)

    fun clearResult(key: String) {
        entry.savedStateHandle.remove<Any>(key)
    }

    /**
     * Leaves once the screen is settled, for exits driven by something other than a tap — a
     * sign-in completing, a save finishing. Such an event can land while the host is still only
     * STARTED, mid-transition, or mid-back-gesture; waiting for RESUMED means the exit survives
     * all three instead of being dropped with no second chance.
     */
    suspend fun backWhenSettled() {
        if (screenLifecycle.currentState == Lifecycle.State.DESTROYED) return
        screenLifecycle.withResumed { back() }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }

/**
 * Runs [onExit] once, when this screen leaves the back stack for good.
 *
 * Not on a configuration change, and not when a predictive-back gesture is started and then
 * cancelled — neither of those pops the entry. Teardown belongs here rather than in the back
 * button's onClick or a [androidx.activity.compose.BackHandler], both of which cover only one of
 * the several ways out of a screen and so drift apart from each other.
 */
@Composable
fun OnScreenExit(onExit: () -> Unit) {
    val navigator = LocalNavigator.current
    val currentOnExit by rememberUpdatedState(onExit)

    DisposableEffect(navigator) {
        var fired = false
        fun fire() {
            if (!fired) {
                fired = true
                currentOnExit()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) fire()
        }
        navigator.screenLifecycle.addObserver(observer)

        onDispose {
            navigator.screenLifecycle.removeObserver(observer)
            // Whether ON_DESTROY beats this disposal is not ordered by the navigation library,
            // so leaving the stack is checked directly as well; fire() dedupes the two.
            if (!navigator.isOnBackStack) fire()
        }
    }
}
