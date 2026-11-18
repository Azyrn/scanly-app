package com.skeler.scanely.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reset on input changes so copy and export never see a previous document's parsed text. */
@Composable
internal fun <T> rememberBackgroundValue(input: Any?, initial: T, parse: () -> T): T = key(input) {
    produceState(initial) { value = computeForDisplay(parse) }.value
}

internal suspend fun <T> computeForDisplay(parse: () -> T): T = withContext(Dispatchers.Default) { parse() }
