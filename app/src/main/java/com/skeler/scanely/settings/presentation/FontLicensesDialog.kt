package com.skeler.scanely.settings.presentation

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.signature.TypedSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FontLicenses"

/**
 * The fonts in `assets/fonts` and the icon set in `res/drawable/ic_tool_*`, each with the licence
 * that ships beside it.
 *
 * The OFL and MIT licences require their text to travel with the work; showing the file the APK
 * actually carries, rather than a copy in strings, means the two cannot drift apart.
 */
internal val BundledFontLicenses: List<Pair<String, String>> = listOf(
    "Great Vibes" to TypedSignature.LICENSE_ASSET,
    "Carlito" to "fonts/LICENSE-Carlito.txt",
    "Caladea" to "fonts/LICENSE-Caladea.txt",
    "Liberation Serif, Liberation Mono" to "fonts/LICENSE-Liberation.txt",
    "Tabler Icons (PDF tools icons)" to "licenses/LICENSE-TablerIcons.txt"
)

internal fun readLicense(context: Context, assetPath: String): String? =
    runCatching { context.assets.open(assetPath).bufferedReader().use { it.readText() } }
        .onFailure { Log.w(TAG, "Licence asset unreadable", it) }
        .getOrNull()

@Composable
fun FontLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState<List<Pair<String, String?>>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            BundledFontLicenses.map { (name, path) -> name to readLicense(context, path) }
        }
    }
    val unreadable = stringResource(R.string.font_licenses_unreadable)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.font_licenses_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                licenses.forEach { (name, text) ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = text ?: unreadable,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.font_licenses_close))
            }
        }
    )
}
