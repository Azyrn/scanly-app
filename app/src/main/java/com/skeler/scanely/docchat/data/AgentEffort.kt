package com.skeler.scanely.docchat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How hard the assistant thinks on a message, as the person chooses it: Fast, Standard or Deep.
 * [wire] is what the Worker takes (worker/src/agent-config.ts EFFORTS); Deep multiplies each file's
 * credits there (AGENT_EFFORT_CREDITS), so the price is the server's, never this app's.
 */
enum class AgentEffort(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        /** A stored value, or Standard for anything this version does not know. */
        fun fromWire(value: String?): AgentEffort = entries.firstOrNull { it.wire == value } ?: MEDIUM
    }
}

private val Context.agentDataStore by preferencesDataStore(name = "agent")

/** The effort last chosen, kept for the next messages and the next session. */
@Singleton
class AgentEffortPrefs @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val effort: Flow<AgentEffort> = context.agentDataStore.data.map { AgentEffort.fromWire(it[EFFORT]) }

    suspend fun set(effort: AgentEffort) {
        context.agentDataStore.edit { it[EFFORT] = effort.wire }
    }

    private companion object {
        val EFFORT = stringPreferencesKey("effort")
    }
}
