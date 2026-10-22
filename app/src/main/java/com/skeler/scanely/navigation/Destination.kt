package com.skeler.scanely.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
sealed interface Destination {
    @Serializable data object Home : Destination

    @Serializable data object DocumentCapture : Destination

    @Serializable data object ScanReview : Destination

    @Serializable data object TextCompose : Destination

    /** Build Documents with AI. Loads itself from the chat cache; null opens a new chat. */
    @Serializable data class DocChat(val chatId: String? = null) : Destination

    /** The document chat's history; picking a chat hands its id back to [DocChat]. */

    @Serializable data object Results : Destination

    @Serializable data object UnifiedResults : Destination

    @Serializable data object BarcodeScanner : Destination

    @Serializable data object Settings : Destination

    @Serializable data object LookAndFeel : Destination

    @Serializable data object TextRecognition : Destination

    @Serializable data object CustomProvider : Destination

    @Serializable data object History : Destination

    @Serializable data object Auth : Destination

    @Serializable data object PdfStudio : Destination

    @Serializable data class PageEditor(val pageIndex: Int) : Destination

    /** The PDF tools grid behind the home screen's Edit PDF card. */
    @Serializable data object PdfTools : Destination

    /** One tool's pick → options → result flow; [tool] is a `core.pdftools.PdfTool` name. */
    @Serializable data class PdfTool(val tool: String) : Destination

    @Serializable data object Transcribe : Destination

    /** Loads itself from `TranscriptStore`, so it restores after process death like Settings. */
    @Serializable data class Transcript(val id: String) : Destination
}

/**
 * Destinations whose content exists only in memory, in ViewModels that hold no saved state.
 * Navigation restores the back stack after process death, but their ViewModels come back empty,
 * so restoring one shows a screen with nothing on it. [ScanelyNavHost] trims to
 * [Destination.Home] instead.
 *
 * A destination belongs here if arriving at it cold would show no content. Screens that load
 * themselves — Settings, History, Auth, the camera screens' own permission flow — do not.
 */
private val IN_MEMORY_ONLY: Set<KClass<out Destination>> = setOf(
    Destination.ScanReview::class,
    Destination.TextCompose::class,
    Destination.Results::class,
    Destination.UnifiedResults::class,
    Destination.PdfStudio::class,
    Destination.PageEditor::class,
    // The tool's run (picked file, options, result) lives only in its ViewModel.
    Destination.PdfTool::class
)

internal fun NavDestination.holdsOnlyInMemoryState(): Boolean =
    IN_MEMORY_ONLY.any { hasRoute(it) }
