package com.skeler.scanely.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the navigation layer itself — the guard in [Navigator], the [OnScreenExit] contract
 * and the in-memory-destination predicate — on a graph of stub screens, so the assertions are
 * about navigation behaviour and not about any one screen's content.
 */
@RunWith(AndroidJUnit4::class)
class NavigationBehaviorTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setGraph(
        onHomeExit: () -> Unit = {},
        onSettingsExit: () -> Unit = {}
    ) {
        rule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Destination.Home) {
                composable<Destination.Home> {
                    Screen(navController, it) {
                        OnScreenExit(onHomeExit)
                        StubScreen("home")
                    }
                }
                composable<Destination.Settings> {
                    Screen(navController, it) {
                        OnScreenExit(onSettingsExit)
                        StubScreen("settings")
                    }
                }
                composable<Destination.Results> {
                    Screen(navController, it) { StubScreen("results") }
                }
            }
        }
    }

    private fun routedEntries() = navController.currentBackStack.value
        .count { it.destination !is NavGraph }

    @Test
    fun twoClicksInOneFrameNavigateOnce() {
        setGraph()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("home-go").performClick()
        rule.onNodeWithTag("home-go").performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertEquals(2, routedEntries())
        assertTrue(navController.currentDestination!!.hasRoute(Destination.Settings::class))
    }

    @Test
    fun twoBackClicksInOneFramePopOnce() {
        setGraph()
        rule.onNodeWithTag("home-go").performClick()
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("settings-back").performClick()
        rule.onNodeWithTag("settings-back").performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertEquals(1, routedEntries())
        assertTrue(navController.currentDestination!!.hasRoute(Destination.Home::class))
    }

    /** A screen that is no longer on top has lost its right to move the stack. */
    @Test
    fun navigatorFromAnUnderlyingScreenIsInert() {
        lateinit var homeNavigator: Navigator
        rule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Destination.Home) {
                composable<Destination.Home> {
                    Screen(navController, it) {
                        homeNavigator = LocalNavigator.current
                        StubScreen("home")
                    }
                }
                composable<Destination.Settings> {
                    Screen(navController, it) { StubScreen("settings") }
                }
                composable<Destination.Results> {
                    Screen(navController, it) { StubScreen("results") }
                }
            }
        }

        rule.onNodeWithTag("home-go").performClick()
        rule.waitForIdle()

        rule.runOnUiThread { homeNavigator.go(Destination.Results) }
        rule.waitForIdle()

        assertEquals(2, routedEntries())
        assertTrue(navController.currentDestination!!.hasRoute(Destination.Settings::class))
    }

    @Test
    fun screenExitRunsOnceWhenPopped() {
        var exits = 0
        setGraph(onSettingsExit = { exits++ })

        rule.onNodeWithTag("home-go").performClick()
        rule.waitForIdle()
        assertEquals(0, exits)

        rule.onNodeWithTag("settings-back").performClick()
        rule.waitForIdle()

        assertEquals(1, exits)
    }

    /** Navigating deeper is not an exit: the screen is still on the stack, holding its state. */
    @Test
    fun screenExitDoesNotRunWhenCoveredByAnotherScreen() {
        var exits = 0
        setGraph(onHomeExit = { exits++ })

        rule.onNodeWithTag("home-go").performClick()
        rule.waitForIdle()

        assertEquals(0, exits)
    }

    @Test
    fun screenExitRunsWhenTheStackIsTrimmedPastTheScreen() {
        var exits = 0
        setGraph(onSettingsExit = { exits++ })

        rule.onNodeWithTag("home-go").performClick()
        rule.waitForIdle()
        rule.runOnUiThread {
            navController.popBackStack(Destination.Home, inclusive = false)
        }
        rule.waitForIdle()

        assertEquals(1, exits)
        assertEquals(1, routedEntries())
    }

    @Test
    fun onlyInMemoryDestinationsAreMarkedForTrimming() {
        setGraph()

        rule.runOnUiThread { navController.navigate(Destination.Results) }
        rule.waitForIdle()
        assertTrue(navController.currentDestination!!.holdsOnlyInMemoryState())

        rule.runOnUiThread { navController.popBackStack() }
        rule.runOnUiThread { navController.navigate(Destination.Settings) }
        rule.waitForIdle()
        assertTrue(!navController.currentDestination!!.holdsOnlyInMemoryState())
    }
}

@Composable
private fun StubScreen(name: String) {
    val navigator = LocalNavigator.current
    Column {
        Text(
            text = name,
            modifier = Modifier
                .testTag("$name-go")
                .clickable { navigator.go(Destination.Settings) }
        )
        Text(
            text = "back",
            modifier = Modifier
                .testTag("$name-back")
                .clickable { navigator.back() }
        )
    }
}
