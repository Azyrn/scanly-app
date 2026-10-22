package com.skeler.scanely.ui.pdftools

import androidx.lifecycle.ViewModel
import com.skeler.scanely.core.pdfeditor.PdfEntitlementGate
import com.skeler.scanely.core.pdftools.PdfTool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The grid needs the entitlement and which tools exist. Reading the entitlement from the gate
 * rather than the editor's state keeps the badges right before any document has been opened, and
 * when the editor is closed. A tool is listed only once it opens the editor or has a bound spec,
 * so a tool that isn't built yet never shows as a dead tile.
 */
@HiltViewModel
class PdfToolsViewModel @Inject constructor(
    gate: PdfEntitlementGate,
    specs: Map<PdfTool, @JvmSuppressWildcards ToolSpec>
) : ViewModel() {
    val isPro: StateFlow<Boolean> = gate.isPro

    /** In [PdfTool] order. */
    val tools: List<PdfTool> = PdfTool.entries.filter { it.opensEditor || it in specs }
}
