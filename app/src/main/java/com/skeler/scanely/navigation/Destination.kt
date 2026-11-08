package com.skeler.scanely.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
sealed interface Destination {
    @Serializable data object Home : Destination

    @Serializable data object ScanReview : Destination

    /**
     * The scan camera. [mode] is a `core.scan.ScanMode` name and [kind] an `IdDocumentKind` name (null: Document,
     * ID card); [replaceSide] retakes that side of the ID copy already open.
     */
    @Serializable data class ScanCamera(
        val mode: String? = null,
        val kind: String? = null,
        val replaceSide: Int? = null
    ) : Destination

    /** The crop after a photo; its photo lives in `ScanSessionViewModel`. */
    @Serializable data object ScanCrop : Destination

    /** The ID / passport copy; its scans live only in [com.skeler.scanely.ui.viewmodel.IdCardViewModel]. */
    @Serializable data object IdCard : Destination

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
    // The photo being cropped lives only in the scan session.
    Destination.ScanCrop::class,
    // ID scans are kept in memory only, on purpose (privacy); a cold restore goes Home.
    Destination.IdCard::class,
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
