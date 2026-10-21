package com.vibeplayer.ui.net

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vibeplayer.data.prefs.BrowserPreferences
import com.vibeplayer.net.Dlna
import com.vibeplayer.net.MediaNames
import com.vibeplayer.net.NetRepository
import com.vibeplayer.net.NetSource
import com.vibeplayer.net.NetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun typeIcon(t: NetType?): String = when (t) {
    NetType.SFTP -> "🔐"
    NetType.SMB -> "🖥"
    NetType.FTP -> "📂"
    NetType.WEBDAV -> "☁"
    NetType.HTTP -> "🌐"
    NetType.DLNA -> "📡"
    NetType.M3U -> "📺"
    null -> "❔"
}

/**
 * P13: Network home: open a link, saved sources (SFTP, SMB, FTP, WebDAV, web folders, DLNA, M3U), and the
 * DLNA media servers on this Wi-Fi.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NetworkScreen(
    repo: NetRepository,
    preferences: BrowserPreferences,
    onBack: () -> Unit,
    onBrowse: (NetSource) -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by repo.observe().collectAsState(initial = emptyList())
    val recent by preferences.recentLinksFlow.collectAsState(initial = emptyList())
    var link by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NetSource?>(null) }
    var actionsFor by remember { mutableStateOf<NetSource?>(null) }
    var servers by remember { mutableStateOf<List<Dlna.Server>?>(null) }
    var searching by remember { mutableStateOf(false) }

    // Android 17: the home network (NAS, PC, DLNA) needs the local network permission.
    var localAllowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
    }
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { localAllowed = it }
    LaunchedEffect(Unit) { if (!localAllowed) askLocal.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }

    fun openLink(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        scope.launch { preferences.addRecentLink(u) }
        val name = u.substringBefore('?').substringAfterLast('/')
        // A channel list opens as a list; anything else (a video, an HLS stream) plays.
        if (name.endsWith(".m3u", ignoreCase = true)) {
            onBrowse(NetSource(type = NetType.M3U.name, name = name, host = u))
        } else if (u.endsWith("/")) {
            onBrowse(NetSource(type = NetType.HTTP.name, name = u, host = u))
        } else {
            NetLauncher.playLink(context, u)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) } }
            )
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (!localAllowed) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Home network access is off", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Servers on your Wi-Fi (NAS, PC, DLNA) need the Nearby devices permission. Internet links work without it.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", context.packageName, null)))
                        }) { Text("Open app permissions") }
                    }
                }
            }
            item { Section("Open a link") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        singleLine = true,
                        placeholder = { Text("Video, .m3u8, .m3u or rtsp:// link", maxLines = 1) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "net_link" }
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { openLink(link) }, modifier = Modifier.semantics { contentDescription = "net_link_open" }) { Text("Open") }
                }
            }
            items(recent, key = { "r:$it" }) { r ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { openLink(r) }).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🕘  $r", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { preferences.removeRecentLink(r) } }) { Text("✕", fontSize = 13.sp) }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Section("Saved", Modifier.weight(1f))
                    TextButton(onClick = { editing = NetSource(type = NetType.SFTP.name, name = "", host = "") },
                        modifier = Modifier.semantics { contentDescription = "net_add" }) { Text("+ Add") }
                }
            }
            if (sources.isEmpty()) {
                item {
                    Text(
                        "No saved sources. Add an SFTP or SMB server, FTP, WebDAV (Nextcloud), a web folder, a DLNA server or an IPTV playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            items(sources, key = { "s:${it.id}" }) { s ->
                SourceRow(
                    icon = typeIcon(s.netType),
                    title = s.name,
                    subtitle = "${s.netType?.label ?: s.type} · ${s.host}${if (s.path.isNotBlank()) " " + s.path else ""}",
                    onClick = { onBrowse(s) },
                    onLongClick = { actionsFor = s }
                )
            }

            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Section("Media servers on this Wi-Fi (DLNA)", Modifier.weight(1f))
                    TextButton(
                        enabled = !searching,
                        onClick = {
                            searching = true
                            scope.launch {
                                servers = withContext(Dispatchers.IO) { runCatching { repo.discoverDlna() }.getOrDefault(emptyList()) }
                                searching = false
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "net_dlna_search" }
                    ) { Text(if (searching) "Searching…" else "Search") }
                }
            }
            if (searching) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            servers?.let { found ->
                if (found.isEmpty() && !searching) {
                    item {
                        Text(
                            "None answered. Is DLNA sharing on in Jellyfin, Plex, Kodi, MiniDLNA or Windows media streaming, on the same Wi-Fi?",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                items(found, key = { "d:${it.location}" }) { d ->
                    SourceRow(
                        icon = "📡", title = d.name, subtitle = d.location,
                        onClick = { onBrowse(NetSource(type = NetType.DLNA.name, name = d.name, host = d.location)) },
                        onLongClick = { editing = NetSource(type = NetType.DLNA.name, name = d.name, host = d.location) }
                    )
                }
            }
        }
    }

    actionsFor?.let { s ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(s.name) },
            text = { Text("${s.netType?.label ?: s.type}\n${s.host}") },
            confirmButton = { TextButton(onClick = { editing = s; actionsFor = null }) { Text("Edit") } },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { repo.delete(s.id) }
                    actionsFor = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    editing?.let { s ->
        SourceDialog(
            initial = s,
            onDismiss = { editing = null },
            onSave = { source, password ->
                scope.launch { repo.save(source, password) }
                editing = null
            }
        )
    }
}

@Composable
private fun Section(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SourceRow(icon: String, title: String, subtitle: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "net_row_$title" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(start = 56.dp))
}

/** Add or edit a source. The password field left empty keeps the saved one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceDialog(initial: NetSource, onDismiss: () -> Unit, onSave: (NetSource, String?) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(initial.netType ?: NetType.SFTP) }
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var path by remember { mutableStateOf(initial.path) }
    var user by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    val editingSaved = initial.id != 0L
    val pickPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            host = uri.toString()
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingSaved) "Edit source" else "Add a source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetType.entries.forEach { t ->
                        FilterChip(selected = t == type, onClick = { type = t }, label = { Text("${typeIcon(t)} ${t.label}", fontSize = 12.sp) })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type.usesUrl) {
                    OutlinedTextField(
                        host, { host = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(when (type) {
                                NetType.WEBDAV -> "Address, e.g. https://cloud.example/remote.php/dav/files/me/"
                                NetType.HTTP -> "Folder address, e.g. http://192.168.1.10:8000/movies/"
                                NetType.DLNA -> "Device description URL (Search finds it)"
                                else -> "Playlist URL (or pick a file)"
                            })
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    if (type == NetType.M3U) OutlinedButton(onClick = { pickPlaylist.launch(arrayOf("*/*")) }) { Text("Pick an .m3u file") }
                } else {
                    OutlinedTextField(host, { host = it }, label = { Text("Server name or address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (empty = ${type.defaultPort})") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(
                        path, { path = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (type == NetType.SMB) "Share and folder, e.g. /Movies or /Media/Anime" else "Start folder (optional)") }
                    )
                }
                if (type != NetType.DLNA) {
                    OutlinedTextField(user, { user = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (type == NetType.SMB) "User (DOMAIN\\user; empty = guest)" else "User (optional)") })
                    OutlinedTextField(
                        password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text(if (editingSaved && initial.secret != null) "Password (empty = keep)" else "Password") }
                    )
                    Text("Passwords are stored encrypted with a key kept in the phone's secure hardware.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && (type != NetType.SMB || path.trim('/').isNotEmpty()),
                onClick = {
                    val source = initial.copy(
                        type = type.name,
                        name = name.ifBlank { host.substringAfter("://").substringBefore('/') },
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 0,
                        path = path.trim(),
                        username = user.trim()
                    )
                    // Empty = keep the saved password; a new source with no password has none.
                    onSave(source, if (password.isEmpty() && editingSaved) null else password)
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** For the M3U screen: whether a listing is long enough to want a search field. */
internal fun wantsSearch(count: Int): Boolean = count > 30

internal fun sizeText(size: Long): String = when {
    size < 0 -> ""
    size >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", size / (1L shl 30).toDouble())
    size >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", size / (1L shl 20).toDouble())
    else -> "${size / 1024} KB"
}

internal fun isPlayable(name: String, playUrl: String?): Boolean =
    !MediaNames.isSubtitle(name) && (playUrl != null || MediaNames.isVideo(name))
