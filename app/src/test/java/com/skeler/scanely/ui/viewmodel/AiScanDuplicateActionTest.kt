package com.skeler.scanely.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.core.ai.*
import com.skeler.scanely.core.billing.EntitlementRepository
import com.skeler.scanely.core.ocr.PdfRendererHelper
import com.skeler.scanely.history.data.HistoryManager
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class AiScanDuplicateActionTest {
    private val service = mock(GenerativeAiService::class.java)
    private val entitlement = mock(EntitlementRepository::class.java)
    private lateinit var vm: AiScanViewModel
    private val pending = flow<AiEvent> { awaitCancellation() }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = AiScanViewModel(service, mock(HistoryManager::class.java),
            entitlement, mock(PdfRendererHelper::class.java), mock(FeatureSwitches::class.java))
    }
    @After fun cleanup() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun `double tapping scan does not cancel and rebill it`() {
        val uri = mock(Uri::class.java)
        `when`(service.processFileEvents(uri, null, null)).thenReturn(pending)
        repeat(2) { vm.processImage(uri, AiMode.EXTRACT_TEXT) }
        verify(service, times(1)).processFileEvents(uri, null, null)
    }

    @Test fun `double tapping batch scan starts only one first page`() {
        val uri = mock(Uri::class.java)
        `when`(service.processFileEvents(uri, null, null)).thenReturn(pending)
        repeat(2) { vm.processMultipleFiles(listOf(uri), AiMode.EXTRACT_TEXT) }
        verify(service, times(1)).processFileEvents(uri, null, null)
    }

    @Test fun `revoked file access becomes an error result instead of a stuck scan`() {
        val uri = mock(Uri::class.java)
        `when`(service.processFileEvents(uri, null, null)).thenReturn(flow { throw SecurityException("revoked") })
        vm.processImage(uri, AiMode.EXTRACT_TEXT)
        org.junit.Assert.assertFalse(vm.aiState.value.isProcessing)
        org.junit.Assert.assertTrue(vm.aiState.value.result is AiResult.Error)
    }

    @Test fun `translation refreshes server quota after spending credits`() = runBlocking {
        vm.updateText("source")
        `when`(service.translateText("source", "French")).thenReturn(AiResult.Success("texte"))
        vm.translateResult("French")
        verify(entitlement).refresh()
    }

    @Test fun `double tapping translate starts only one billed call`() = runBlocking {
        vm.updateText("source")
        doAnswer { COROUTINE_SUSPENDED }.`when`(service).translateText("source", "French")
        repeat(2) { vm.translateResult("French") }
        verify(service, times(1)).translateText("source", "French")
        Unit
    }
}
