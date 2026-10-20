package com.vibeplayer.ui.browser

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.media.VideoFileActions
import com.vibeplayer.data.media.VideoFolder
import com.vibeplayer.data.media.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long-press on a folder: hide it from the browser (P12-06). Settings > Hidden folders shows it again. */
@Composable
fun FolderActionsDialog(folder: VideoFolder, onHide: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = { Text("Hide this folder from the list, search and Continue Watching? Settings > Hidden folders shows it again.") },
        confirmButton = {
            TextButton(onClick = { onHide(); onDismiss() }, modifier = Modifier.semantics { contentDescription = "hide_folder" }) {
                Text("Hide")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private enum class VideoActionStep { MENU, RENAME, CONFIRM_DELETE }

/**
 * Long-press on a video: Rename or Delete (P12-05). Both ask Android for consent (the app may only read
 * media); history, position and bookmarks follow the file. Set [target] to a video to open the menu.
 */
@Composable
fun VideoActionsHost(target: VideoItem?, db: AppDatabase, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember(target) { mutableStateOf(VideoActionStep.MENU) }
    var pendingName by remember { mutableStateOf<String?>(null) }
    var pendingVideo by remember { mutableStateOf<VideoItem?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val video = pendingVideo
        val newName = pendingName
        pendingVideo = null
        pendingName = null
        if (video == null) return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            Log.i("VP", "File action on ${video.displayName} not allowed by the user")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (newName == null) {
                    // The delete request already deleted the file.
                    VideoFileActions.deleteHistory(db, video.path, video.sizeBytes)
                    true
                } else {
                    VideoFileActions.rename(context.contentResolver, video.uri, newName).also { renamed ->
                        if (renamed) VideoFileActions.moveHistory(db, video.path, newName, video.sizeBytes)
                    }
                }
            }
            val message = when {
                newName == null -> "Deleted ${video.displayName}"
                ok -> "Renamed to $newName"
                else -> "Could not rename ${video.displayName}"
            }
            Log.i("VP", "File action: $message")
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun ask(video: VideoItem, newName: String?) {
        try {
            val sender = if (newName == null) VideoFileActions.deleteRequest(context.contentResolver, video.uri)
            else VideoFileActions.writeRequest(context.contentResolver, video.uri)
            pendingVideo = video
            pendingName = newName
            consent.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            Log.w("VP", "File action request for ${video.uri} failed: ${e.message}")
            Toast.makeText(context, "Not possible for this file", Toast.LENGTH_SHORT).show()
        }
        onDone()
    }

    val video = target ?: return
    when (step) {
        VideoActionStep.MENU -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(video.displayName, maxLines = 2) },
            text = { Text("Rename or delete this video. Android asks you to allow it first.") },
            confirmButton = {
                TextButton(onClick = { step = VideoActionStep.RENAME }, modifier = Modifier.semantics { contentDescription = "rename_video" }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { step = VideoActionStep.CONFIRM_DELETE }, modifier = Modifier.semantics { contentDescription = "delete_video" }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        )
        VideoActionStep.RENAME -> {
            val base = video.displayName.substringBeforeLast('.', video.displayName)
            var field by remember(video) { mutableStateOf(TextFieldValue(video.displayName, TextRange(0, base.length))) }
            val newName = VideoFileActions.renamedName(video.displayName, field.text)
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("Rename") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = field,
                            onValueChange = { field = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "rename_field" }
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = newName != null, onClick = { newName?.let { ask(video, it) } }) { Text("Rename") }
                },
                dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
            )
        }
        VideoActionStep.CONFIRM_DELETE -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Delete video?") },
            text = { Text("${video.displayName} is deleted from the phone, with its position and bookmarks.") },
            confirmButton = {
                TextButton(onClick = { ask(video, null) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
        )
    }
}
