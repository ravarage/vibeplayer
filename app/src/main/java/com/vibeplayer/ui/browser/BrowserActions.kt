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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** A rename (newName) or delete (null) waiting for Android's consent. */
data class PendingFileAction(
    val uri: String,
    val path: String,
    val sizeBytes: Long,
    val displayName: String,
    val newName: String?
) {
    companion object {
        /** Kept in the saved state, so it survives the activity being recreated behind the consent dialog. */
        val Saver = androidx.compose.runtime.saveable.Saver<PendingFileAction?, ArrayList<String>>(
            save = { p -> p?.let { arrayListOf(it.uri, it.path, it.sizeBytes.toString(), it.displayName) + listOfNotNull(it.newName) }
                ?.let { ArrayList(it) } ?: arrayListOf() },
            restore = { v ->
                if (v.size < 4) null
                else PendingFileAction(v[0], v[1], v[2].toLongOrNull() ?: 0L, v[3], v.getOrNull(4))
            }
        )
    }
}

/**
 * Long-press on a video: Rename or Delete (P12-05). Both ask Android for consent (the app may only read
 * media); history, position and bookmarks follow the file. Set [target] to a video to open the menu.
 */
@Composable
fun VideoActionsHost(target: VideoItem?, db: AppDatabase, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember(target) { mutableStateOf(VideoActionStep.MENU) }
    // Saveable: the activity can be recreated while Android's consent dialog is up (rotation, low memory).
    var pending by rememberSaveable(stateSaver = PendingFileAction.Saver) { mutableStateOf<PendingFileAction?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = pending
        pending = null
        if (action == null) return@rememberLauncherForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            Log.i("VP", "File action on ${action.displayName} not allowed by the user")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val newName = action.newName
            val finalName = withContext(Dispatchers.IO) {
                if (newName == null) {
                    // The delete request already deleted the file.
                    VideoFileActions.deleteHistory(db, action.path, action.sizeBytes)
                    null
                } else {
                    val uri = android.net.Uri.parse(action.uri)
                    if (!VideoFileActions.rename(context.contentResolver, uri, newName)) return@withContext null
                    // MediaStore may adjust the name (a clash adds " (1)"): move the history to the name it kept.
                    val kept = VideoFileActions.displayName(context.contentResolver, uri) ?: newName
                    VideoFileActions.moveHistory(db, action.path, kept, action.sizeBytes)
                    kept
                }
            }
            val message = when {
                newName == null -> "Deleted ${action.displayName}"
                finalName != null -> "Renamed to $finalName"
                else -> "Could not rename ${action.displayName}"
            }
            Log.i("VP", "File action: $message")
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun ask(video: VideoItem, newName: String?) {
        try {
            val sender = if (newName == null) VideoFileActions.deleteRequest(context.contentResolver, video.uri)
            else VideoFileActions.writeRequest(context.contentResolver, video.uri)
            pending = PendingFileAction(video.uri.toString(), video.path, video.sizeBytes, video.displayName, newName)
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
