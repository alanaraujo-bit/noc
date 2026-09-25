package com.noc.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("noc_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val activePcId: String? = null,
    val onboardingDone: Boolean = false,
    /** Diagnóstico: ignora a rede local e força o caminho remoto (relay). */
    val forceRelay: Boolean = false,
    val defaultPresetId: String? = null,
    val lastModel: String? = null,
    val recentModels: List<String> = emptyList(),
    val haptics: Boolean = true,
    val showStats: Boolean = true,
    val notifyWhenDone: Boolean = true,
    val sendWithEnter: Boolean = false,
    val showReasoning: Boolean = true,
)

class Prefs(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val activePc = stringPreferencesKey("active_pc")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val forceRelay = booleanPreferencesKey("force_relay")
        val defaultPreset = stringPreferencesKey("default_preset")
        val lastModel = stringPreferencesKey("last_model")
        val recentModels = stringPreferencesKey("recent_models")
        val haptics = booleanPreferencesKey("haptics")
        val showStats = booleanPreferencesKey("show_stats")
        val notifyDone = booleanPreferencesKey("notify_done")
        val sendEnter = booleanPreferencesKey("send_enter")
        val showReasoning = booleanPreferencesKey("show_reasoning")
    }

    val flow: Flow<AppPrefs> = context.dataStore.data.map { p ->
        AppPrefs(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            activePcId = p[K.activePc],
            onboardingDone = p[K.onboarding] ?: false,
            forceRelay = p[K.forceRelay] ?: false,
            defaultPresetId = p[K.defaultPreset],
            lastModel = p[K.lastModel],
            recentModels = p[K.recentModels]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            haptics = p[K.haptics] ?: true,
            showStats = p[K.showStats] ?: true,
            notifyWhenDone = p[K.notifyDone] ?: true,
            sendWithEnter = p[K.sendEnter] ?: false,
            showReasoning = p[K.showReasoning] ?: true,
        )
    }

    suspend fun current(): AppPrefs = flow.first()

    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[K.theme] = t.name }
    suspend fun setActivePc(id: String?) = context.dataStore.edit { if (id == null) it.remove(K.activePc) else it[K.activePc] = id }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[K.onboarding] = v }
    suspend fun setForceRelay(v: Boolean) = context.dataStore.edit { it[K.forceRelay] = v }
    suspend fun setDefaultPreset(id: String?) = context.dataStore.edit { if (id == null) it.remove(K.defaultPreset) else it[K.defaultPreset] = id }
    suspend fun setHaptics(v: Boolean) = context.dataStore.edit { it[K.haptics] = v }
    suspend fun setShowStats(v: Boolean) = context.dataStore.edit { it[K.showStats] = v }
    suspend fun setNotifyWhenDone(v: Boolean) = context.dataStore.edit { it[K.notifyDone] = v }
    suspend fun setSendWithEnter(v: Boolean) = context.dataStore.edit { it[K.sendEnter] = v }
    suspend fun setShowReasoning(v: Boolean) = context.dataStore.edit { it[K.showReasoning] = v }

    /** Registra o modelo usado (alimenta "modelos recentes"). */
    suspend fun noteModelUsed(key: String) = context.dataStore.edit { p ->
        val list = (listOf(key) + (p[K.recentModels]?.split('\n') ?: emptyList())).filter { it.isNotBlank() }.distinct().take(6)
        p[K.recentModels] = list.joinToString("\n")
        p[K.lastModel] = key
    }
}
