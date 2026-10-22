package com.skeler.scanely.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Declaring a [Destination] and forgetting to register it is a silent failure: the app compiles,
 * and the screen is simply unreachable — or reachable and fatal, for one that something already
 * navigates to. Reading the two sources keeps that mistake to a failing test rather than a crash
 * a user finds.
 */
class NavGraphCoverageTest {

    private val destinationSource = File("src/main/java/com/skeler/scanely/navigation/Destination.kt")
    private val navHostSource = File("src/main/java/com/skeler/scanely/navigation/ScanelyNavHost.kt")

    private val declared: Set<String> by lazy {
        Regex("""data (?:object|class) (\w+) *[(:]""")
            .findAll(destinationSource.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private val registered: Set<String> by lazy {
        Regex("""composable<Destination\.(\w+)>""")
            .findAll(navHostSource.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun sourcesAreWhereTheTestExpects() {
        assertTrue(destinationSource.absolutePath, destinationSource.isFile)
        assertTrue(navHostSource.absolutePath, navHostSource.isFile)
        assertTrue("no destinations parsed", declared.size > 1)
    }

    @Test
    fun everyDestinationIsRegisteredInTheGraph() {
        assertEquals(emptySet<String>(), declared - registered)
    }

    @Test
    fun everyRegisteredRouteIsADeclaredDestination() {
        assertEquals(emptySet<String>(), registered - declared)
    }
}
