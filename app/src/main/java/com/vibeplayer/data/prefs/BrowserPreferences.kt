package com.vibeplayer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class VideoSortOrder(val displayName: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_DESC("Date (Newest)"),
    DATE_ASC("Date (Oldest)"),
    SIZE_DESC("Size (Largest)"),
    SIZE_ASC("Size (Smallest)")
}

val Context.browserDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "browser_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class BrowserPreferences(private val context: Context) {
    private val sortOrderKey = stringPreferencesKey("video_sort_order")

    val sortOrderFlow: Flow<VideoSortOrder> = context.browserDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
        val name = preferences[sortOrderKey] ?: VideoSortOrder.DATE_DESC.name
        try {
            VideoSortOrder.valueOf(name)
        } catch (e: Exception) {
            VideoSortOrder.DATE_DESC
        }
    }

    suspend fun setSortOrder(order: VideoSortOrder) {
        context.browserDataStore.edit { preferences ->
            preferences[sortOrderKey] = order.name
        }
    }
}
