package com.vibeplayer.ui.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.vibeplayer.data.subs.SubtitleMatcher
import com.vibeplayer.net.MediaNames
import com.vibeplayer.net.NetFileProvider
import com.vibeplayer.net.NetPlaylist
import com.vibeplayer.net.RemoteEntry
import com.vibeplayer.ui.player.PlayerActivity

/** P13: starts the player on a network entry, with the listing it came from as the playlist. */
object NetLauncher {
    fun identity(context: Context, sourceId: Long, e: RemoteEntry): String =
        e.playUrl ?: NetFileProvider.uri(context.packageName, sourceId, e.path).toString()

    fun play(context: Context, sourceId: Long, entry: RemoteEntry, listing: List<RemoteEntry>) {
        val playable = listing.filter { !it.isDir && (it.playUrl != null || MediaNames.isVideo(it.name)) }
        val subtitleFiles = listing.filter { !it.isDir && MediaNames.isSubtitle(it.name) }
        NetPlaylist.set(playable.map { e ->
            val subs = SubtitleMatcher.select(e.name, subtitleFiles.map { it.name }).mapNotNull { n ->
                subtitleFiles.firstOrNull { it.name == n }?.let { identity(context, sourceId, it) to it.name }
            }
            NetPlaylist.Item(identity(context, sourceId, e), e.name, e, sourceId, subs)
        })
        play(context, identity(context, sourceId, entry), entry.name, entry)
    }

    /** A single link (Open a link, a recent link): no list around it. */
    fun playLink(context: Context, url: String) {
        NetPlaylist.set(emptyList())
        play(context, url, url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }, null)
    }

    private fun play(context: Context, identity: String, title: String, entry: RemoteEntry?) {
        Log.i("VP-Net", "Play $title")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(identity)).apply {
            setClassName(context.packageName, PlayerActivity::class.java.name)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            entry?.let { e ->
                if (e.headers.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_NET_HEADERS, ArrayList(e.headers))
                e.userAgent?.let { putExtra(PlayerActivity.EXTRA_NET_USER_AGENT, it) }
                e.referrer?.let { putExtra(PlayerActivity.EXTRA_NET_REFERRER, it) }
                putExtra(PlayerActivity.EXTRA_NET_LIVE, e.live)
            }
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
