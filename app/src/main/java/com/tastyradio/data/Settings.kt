package com.tastyradio.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * The settings that earn their place. No theme picker: the app follows the device, which is what
 * the reference app's default is anyway.
 */
class Settings(private val context: Context) {

    enum class RefreshFrequency(val label: String) {
        Off("Never"),
        Weekly("Weekly"),
        Daily("Daily"),
    }

    data class Values(
        /** Bigger buffers: fewer dropouts on flaky mobile data, slower to start. */
        val largeBuffer: Boolean = true,
        val refresh: RefreshFrequency = RefreshFrequency.Weekly,
    )

    val values: Flow<Values> = context.dataStore.data.map { preferences ->
        Values(
            largeBuffer = preferences[LARGE_BUFFER] ?: true,
            refresh = preferences[REFRESH]?.let { stored ->
                runCatching { RefreshFrequency.valueOf(stored) }.getOrNull()
            } ?: RefreshFrequency.Weekly,
        )
    }

    suspend fun setLargeBuffer(enabled: Boolean) = put(LARGE_BUFFER, enabled)

    suspend fun setRefresh(frequency: RefreshFrequency) {
        context.dataStore.edit { it[REFRESH] = frequency.name }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val LARGE_BUFFER = booleanPreferencesKey("largeBuffer")
        val REFRESH = stringPreferencesKey("refreshFrequency")
    }
}
