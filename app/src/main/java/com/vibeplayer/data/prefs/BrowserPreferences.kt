package com.vibeplayer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    private val hiddenFoldersKey = stringSetPreferencesKey("hidden_folders")

    /** P12-06: folders left out of the folder list, search and Continue Watching. */
    val hiddenFoldersFlow: Flow<List<HiddenFolder>> = context.browserDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            (preferences[hiddenFoldersKey] ?: emptySet()).mapNotNull(HiddenFolder::decode).sortedBy { it.name.lowercase() }
        }

    suspend fun setFolderHidden(folder: HiddenFolder, hidden: Boolean) {
        context.browserDataStore.edit { preferences ->
            val current = (preferences[hiddenFoldersKey] ?: emptySet()).filterNot { HiddenFolder.decode(it)?.bucketId == folder.bucketId }.toSet()
            preferences[hiddenFoldersKey] = if (hidden) current + folder.encode() else current
        }
    }

    private val recentLinksKey = stringPreferencesKey("recent_links")

    /** P13: links opened in Network > Open a link, newest first. */
    val recentLinksFlow: Flow<List<String>> = context.browserDataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { p -> p[recentLinksKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList() }

    suspend fun addRecentLink(link: String) {
        context.browserDataStore.edit { p ->
            val old = p[recentLinksKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            p[recentLinksKey] = (listOf(link) + old.filter { it != link }).take(10).joinToString("\n")
        }
    }

    suspend fun removeRecentLink(link: String) {
        context.browserDataStore.edit { p ->
            val old = p[recentLinksKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            p[recentLinksKey] = old.filter { it != link }.joinToString("\n")
        }
    }

    suspend fun setSortOrder(order: VideoSortOrder) {
        context.browserDataStore.edit { preferences ->
            preferences[sortOrderKey] = order.name
        }
    }
}

/** A hidden folder: the MediaStore bucket and its name for the Settings list. */
data class HiddenFolder(val bucketId: String, val name: String) {
    fun encode(): String = "$bucketId\t$name"

    companion object {
        fun decode(value: String): HiddenFolder? {
            val i = value.indexOf('\t')
            return if (i <= 0) null else HiddenFolder(value.substring(0, i), value.substring(i + 1))
        }
    }
}
