package com.vibeplayer.net

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Kinds of network source (P13). */
enum class NetType(val label: String, val defaultPort: Int, val usesUrl: Boolean) {
    SFTP("SFTP (SSH)", 22, false),
    SMB("SMB / Windows share", 445, false),
    FTP("FTP", 21, false),
    WEBDAV("WebDAV (Nextcloud, NAS)", 0, true),
    HTTP("Web folder (HTTP listing)", 0, true),
    DLNA("DLNA / UPnP media server", 0, true),
    M3U("M3U playlist (IPTV)", 0, true);

    companion object {
        fun of(name: String): NetType? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A saved network source. [host] is the server name or address, or the URL for the URL types (WebDAV,
 * HTTP, DLNA description, M3U playlist; an M3U may also be a content:// file). [path] is the start folder
 * (SMB: "/Share/folder"). [secret] is the password encrypted with [Secrets] (Android Keystore), never plain.
 */
@Entity(tableName = "network_sources")
data class NetSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val type: String,
    val name: String,
    val host: String,
    val port: Int = 0,
    val path: String = "",
    val username: String = "",
    val secret: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val netType: NetType? get() = NetType.of(type)
}

@Dao
interface NetSourceDao {
    @Query("SELECT * FROM network_sources ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<NetSource>>

    @Query("SELECT * FROM network_sources WHERE id = :id")
    suspend fun get(id: Long): NetSource?

    @Insert
    suspend fun insert(source: NetSource): Long

    @Update
    suspend fun update(source: NetSource)

    @Query("DELETE FROM network_sources WHERE id = :id")
    suspend fun delete(id: Long)
}
