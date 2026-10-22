package com.skeler.scanely.core.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.skeler.scanely.core.network.FeaturesResponse
import com.skeler.scanely.core.network.ScanlyPublicApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The AI features the server has switched off (`worker/src/features.ts`), so an unpaid or broken
 * provider takes its feature out of the app without a release instead of failing on every tap.
 *
 * Starts from the last answer the server gave. A refresh that fails keeps it: the route refuses a
 * switched-off feature on its own, so a stale "on" costs one clear error, never a charge.
 */
@Singleton
class FeatureSwitches internal constructor(
    private val api: ScanlyPublicApi,
    private val store: DisabledFeatureStore
) {
    @Inject constructor(api: ScanlyPublicApi, @ApplicationContext context: Context) :
        this(api, DataStoreDisabledFeatures(context))

    private val _disabled = MutableStateFlow<Set<AiFeature>?>(null)

    /** Null until the stored answer has been read; after that, never null again. */
    val disabled: StateFlow<Set<AiFeature>?> = _disabled.asStateFlow()

    private val _agentEnabled = MutableStateFlow(false)

    /**
     * Whether chat turns may go to the document agent. Opt-in, unlike [disabled]: on only when the
     * server says `agent: true`, which it does only with its kill switch on and its config complete.
     */
    val agentEnabled: StateFlow<Boolean> = _agentEnabled.asStateFlow()

    suspend fun refresh() {
        if (_disabled.value == null) {
            val stored = store.load()
            _disabled.value = featuresNamed(stored)
            _agentEnabled.value = AGENT_ON in stored
        }
        val answer = try {
            api.features()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            return // Offline or a server fault: the last known answer stands.
        }
        val off = disabledFeatures(answer)
        val agent = answer.features[AGENT] == true
        _disabled.value = off
        _agentEnabled.value = agent
        store.save(off.mapTo(mutableSetOf()) { it.name } + setOfNotNull(AGENT_ON.takeIf { agent }))
    }

    private companion object {
        const val AGENT = "agent"

        /** Stored beside the disabled names; no AiFeature is called this. */
        const val AGENT_ON = "+agent"
    }
}

/** Features [response] switches off. Names the app does not know are ignored. */
internal fun disabledFeatures(response: FeaturesResponse): Set<AiFeature> =
    AiFeature.entries.filterTo(mutableSetOf()) { response.features[it.name.lowercase()] == false }

private fun featuresNamed(names: Set<String>): Set<AiFeature> =
    AiFeature.entries.filterTo(mutableSetOf()) { it.name in names }

/**
 * Whether this feature's entry point shows. A switch covers Scanly's own provider; with a custom
 * endpoint every feature but a [CustomRouting.SCANLY_ONLY] one runs without it.
 */
fun AiFeature.isOffered(disabled: Set<AiFeature>, customProvider: Boolean): Boolean =
    this !in disabled || (customProvider && routing.usesCustomEndpoint)

val AiMode.feature: AiFeature
    get() = when (this) {
        AiMode.EXTRACT_TEXT, AiMode.EXTRACT_PDF_TEXT, AiMode.EXTRACT_DATA -> AiFeature.SCAN
        AiMode.DESIGN_DOCUMENT -> AiFeature.DESIGN
        AiMode.TRANSCRIBE_AUDIO -> AiFeature.TRANSCRIBE
        AiMode.ICON_TRANSLATE -> AiFeature.TRANSLATE
    }

/** The AI Scan card leads only to the sheet, so it goes when nothing in the sheet is offered. */
fun showsAiScanCard(offered: Set<AiFeature>): Boolean =
    AiMode.entries.any { it != AiMode.ICON_TRANSLATE && it.feature in offered }

internal interface DisabledFeatureStore {
    suspend fun load(): Set<String>
    suspend fun save(names: Set<String>)
}

private val Context.featureSwitchStore by preferencesDataStore(name = "feature_switches")

private class DataStoreDisabledFeatures(private val context: Context) : DisabledFeatureStore {
    override suspend fun load(): Set<String> =
        context.featureSwitchStore.data.first()[DISABLED].orEmpty()

    override suspend fun save(names: Set<String>) {
        context.featureSwitchStore.edit { it[DISABLED] = names }
    }

    private companion object {
        val DISABLED = stringSetPreferencesKey("disabled")
    }
}
