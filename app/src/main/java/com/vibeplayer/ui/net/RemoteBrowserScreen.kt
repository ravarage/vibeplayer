package com.vibeplayer.ui.net

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.net.MediaNames
import com.vibeplayer.net.NetRepository
import com.vibeplayer.net.NetSource
import com.vibeplayer.net.RemoteEntry

/**
 * P13: browses one network source: folders open in place (Back goes up), videos and channels play with
 * the listing as their playlist. The connection is kept while the screen is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBrowserScreen(repo: NetRepository, source: NetSource, onBack: () -> Unit) {
    val context = LocalContext.current
    val session = remember(source) { com.vibeplayer.net.RemoteSession(repo, source) }
    val stack = remember { mutableStateListOf<Pair<String, String>>() } // (path, title)
    var entries by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // Bumped on every folder change; the stack itself is also filled from the loading coroutine.
    var nav by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(source, nav, reload) {
        entries = null
        error = null
        try {
            val result = session.list(stack.lastOrNull()?.first)
            if (stack.isEmpty()) stack.add(result.second to source.name)
            entries = result.first
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VP-Net", "Listing ${source.name} failed: ${e.message}")
            error = NetRepository.explain(e)
        }
    }

    BackHandler {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            filter = ""
            nav++
        } else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stack.lastOrNull()?.second ?: source.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        if (stack.size > 1) {
                            Text(stack.drop(1).joinToString(" › ") { it.second }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (stack.size > 1) {
                            stack.removeAt(stack.lastIndex)
                            nav++
                        } else onBack()
                    }) { Text("←", fontSize = 22.sp) }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val list = entries
            when {
                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(error ?: "", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { reload++ }) { Text("Retry") }
                }
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text("Nothing to play here", Modifier.align(Alignment.Center))
                else -> {
                    val shown = if (filter.isBlank()) list else list.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (wantsSearch(list.size)) {
                            item {
                                OutlinedTextField(
                                    value = filter, onValueChange = { filter = it }, singleLine = true,
                                    placeholder = { Text("Search ${list.size} items") },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                        .semantics { contentDescription = "net_filter" }
                                )
                            }
                        }
                        items(shown, key = { it.path + "|" + it.name }) { e ->
                            val playable = !e.isDir && isPlayable(e.name, e.playUrl)
                            val icon = when {
                                e.isDir -> "📁"
                                e.live -> "📺"
                                MediaNames.isSubtitle(e.name) -> "💬"
                                else -> "🎬"
                            }
                            val detail = listOfNotNull(
                                e.group?.takeIf { stack.size <= 1 },
                                sizeText(e.size).ifEmpty { null },
                                "live".takeIf { e.live }
                            ).joinToString(" · ")
                            SourceRow(
                                icon = icon, title = e.name, subtitle = detail, enabled = e.isDir || playable,
                                onClick = {
                                    when {
                                        e.isDir -> {
                                            filter = ""
                                            stack.add(e.path to e.name)
                                            nav++
                                        }
                                        playable -> NetLauncher.play(context, source.id, e, list)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
