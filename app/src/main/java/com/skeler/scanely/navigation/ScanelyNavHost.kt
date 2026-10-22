package com.skeler.scanely.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.skeler.scanely.auth.presentation.screen.AuthScreen
import com.skeler.scanely.core.lookup.LookupOrchestrator
import com.skeler.scanely.core.pdftools.PdfTool
import com.skeler.scanely.docchat.presentation.screen.DocChatScreen
import com.skeler.scanely.docchat.presentation.screen.OPEN_CHAT_RESULT
import com.skeler.scanely.history.presentation.screen.HistoryScreen
import com.skeler.scanely.settings.presentation.screen.CustomProviderScreen
import com.skeler.scanely.settings.presentation.screen.LookAndFeelScreen
import com.skeler.scanely.settings.presentation.screen.SettingsScreen
import com.skeler.scanely.settings.presentation.screen.TextRecognitionScreen
import com.skeler.scanely.ui.pdfeditor.PageEditorScreen
import com.skeler.scanely.ui.pdfeditor.PdfStudioScreen
import com.skeler.scanely.ui.pdftools.PdfToolScreen
import com.skeler.scanely.ui.pdftools.PdfToolsScreen
import com.skeler.scanely.ui.screens.BarcodeScannerScreen
import com.skeler.scanely.ui.screens.DocumentCaptureScreen
import com.skeler.scanely.ui.screens.HomeScreen
import com.skeler.scanely.ui.screens.ResultsScreen
import com.skeler.scanely.ui.screens.ScanReviewScreen
import com.skeler.scanely.ui.screens.TextComposeScreen
import com.skeler.scanely.ui.screens.TranscribeScreen
import com.skeler.scanely.ui.screens.TranscriptScreen
import com.skeler.scanely.ui.screens.UnifiedResultsScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * @param restoredAfterProcessDeath the activity is being rebuilt in a fresh process, so the back
 *   stack has been restored but nothing the ViewModels held has been. See
 *   [holdsOnlyInMemoryState].
 */
@Composable
fun ScanelyNavHost(
    navController: NavHostController = rememberNavController(),
    restoredAfterProcessDeath: Boolean = false,
    /** A chat a notification asked to open; cleared once it is open. */
    chatToOpen: MutableStateFlow<String?> = MutableStateFlow(null)
) {
    val context = LocalContext.current

    val lookupOrchestrator = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LookupOrchestratorEntryPoint::class.java
        ).lookupOrchestrator()
    }

    LaunchedEffect(Unit) {
        if (!restoredAfterProcessDeath) return@LaunchedEffect
        val restored = navController.currentDestination ?: return@LaunchedEffect
        if (restored.holdsOnlyInMemoryState()) {
            navController.popBackStack(Destination.Home, inclusive = false)
        }
    }

    // From a "your document is ready" notification. The chat on top takes it as a result, so
    // there is never a second chat screen stacked on the first.
    LaunchedEffect(chatToOpen) {
        chatToOpen.filterNotNull().collect { chatId ->
            val top = navController.currentBackStackEntry
            if (top?.destination?.hasRoute(Destination.DocChat::class) == true) {
                top.savedStateHandle[OPEN_CHAT_RESULT] = chatId
            } else {
                navController.navigate(Destination.DocChat(chatId))
            }
            chatToOpen.value = null
        }
    }

    NavHost(
        navController = navController,
        startDestination = Destination.Home,
        enterTransition = { gallerySlideEnter() },
        exitTransition = { gallerySlideExit() },
        popEnterTransition = { galleryPopEnter() },
        popExitTransition = { galleryPopExit() },
        predictivePopEnterTransition = { galleryPredictivePopEnter() },
        predictivePopExitTransition = { galleryPredictivePopExit() }
    ) {
        composable<Destination.Home> { Screen(navController, it) { HomeScreen() } }

        composable<Destination.Settings> { Screen(navController, it) { SettingsScreen() } }

        composable<Destination.LookAndFeel> { Screen(navController, it) { LookAndFeelScreen() } }

        composable<Destination.TextRecognition> {
            Screen(navController, it) { TextRecognitionScreen() }
        }

        composable<Destination.CustomProvider> {
            Screen(navController, it) { CustomProviderScreen() }
        }

        composable<Destination.History> { Screen(navController, it) { HistoryScreen() } }

        composable<Destination.Transcribe> { Screen(navController, it) { TranscribeScreen() } }

        composable<Destination.Transcript> { Screen(navController, it) { TranscriptScreen() } }

        composable<Destination.Auth> { Screen(navController, it) { AuthScreen() } }

        composable<Destination.DocumentCapture> {
            Screen(navController, it) { DocumentCaptureScreen() }
        }

        composable<Destination.ScanReview> { Screen(navController, it) { ScanReviewScreen() } }

        composable<Destination.TextCompose> { Screen(navController, it) { TextComposeScreen() } }

        composable<Destination.DocChat> { Screen(navController, it) { DocChatScreen() } }

        composable<Destination.Results> { Screen(navController, it) { ResultsScreen() } }

        composable<Destination.UnifiedResults> {
            Screen(navController, it) { UnifiedResultsScreen() }
        }

        composable<Destination.BarcodeScanner> {
            Screen(navController, it) {
                BarcodeScannerScreen(lookupOrchestrator = lookupOrchestrator)
            }
        }

        composable<Destination.PdfStudio> { Screen(navController, it) { PdfStudioScreen() } }

        composable<Destination.PageEditor> { entry ->
            val route = entry.toRoute<Destination.PageEditor>()
            Screen(navController, entry) { PageEditorScreen(startIndex = route.pageIndex) }
        }

        composable<Destination.PdfTools> { Screen(navController, it) { PdfToolsScreen() } }

        composable<Destination.PdfTool> { entry ->
            val route = entry.toRoute<Destination.PdfTool>()
            Screen(navController, entry) { PdfToolScreen(tool = PdfTool.valueOf(route.tool)) }
        }
    }
}

/**
 * Every destination is opaque and swallows its own touches.
 *
 * Two screens are on screen together for the whole of a predictive-back gesture, which is as long
 * as the user holds a finger down. A screen that paints no background of its own — relying on the
 * single Surface behind the host, as several here used to — lets the screen underneath read
 * straight through it for that entire time, and a screen that does not consume pointer input
 * hands taps to whatever is behind it mid-transition. Enforcing both here rather than screen by
 * screen means a new screen cannot get it wrong.
 *
 * Internal rather than private so tests exercise the real wrapper instead of a copy of it.
 */
@Composable
internal fun Screen(
    navController: NavHostController,
    entry: NavBackStackEntry,
    content: @Composable () -> Unit
) {
    val navigator = remember(entry) { Navigator(navController, entry) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LookupOrchestratorEntryPoint {
    fun lookupOrchestrator(): LookupOrchestrator
}
