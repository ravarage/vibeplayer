package com.vibeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibeplayer.data.db.AppDatabase
import com.vibeplayer.data.db.FileSettings
import com.vibeplayer.data.media.MediaRepository
import com.vibeplayer.data.media.VideoFolder
import com.vibeplayer.ui.browser.FolderListScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repository by lazy { MediaRepository(applicationContext) }
    private val db by lazy { AppDatabase.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        repository = repository,
                        db = db
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    repository: MediaRepository,
    db: AppDatabase
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    var folders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Verify Room round-trip on startup
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val dao = db.fileSettingsDao()
                val testKey = "/sdcard/Movies/vibeplayer-test/ichiban.mp4:10485760"
                val testItem = FileSettings(
                    key = testKey,
                    positionMs = 45000L,
                    durationMs = 120000L,
                    audioTrackId = 1,
                    subTrackId = 2,
                    subEnabled = true,
                    speed = 1.25f,
                    aspect = "fit",
                    lastPlayedAt = System.currentTimeMillis()
                )
                dao.insert(testItem)
                val readBack = dao.getByKey(testKey)
                if (readBack != null && readBack.key == testKey && readBack.positionMs == 45000L) {
                    Log.d("VP", "Room round trip success: key=${readBack.key}, pos=${readBack.positionMs}ms, dur=${readBack.durationMs}ms, speed=${readBack.speed}, aspect=${readBack.aspect}")
                } else {
                    Log.e("VP", "Room round trip failed: readBack=$readBack")
                }
            } catch (e: Exception) {
                Log.e("VP", "Room test failed with exception", e)
            }
        }
    }

    fun loadFolders() {
        isLoading = true
        coroutineScope.launch {
            val list = repository.getFolders()
            folders = list
            isLoading = false
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            Log.d("VP", "READ_MEDIA_VIDEO permission granted by user")
            loadFolders()
        } else {
            Log.w("VP", "READ_MEDIA_VIDEO permission denied by user")
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loadFolders()
        } else {
            launcher.launch(permission)
        }
    }

    if (!hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Permission required to access video library")
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { launcher.launch(permission) }) {
                    Text("Grant Permission")
                }
            }
        }
    } else {
        FolderListScreen(
            folders = folders,
            isLoading = isLoading,
            onFolderClick = { folder ->
                Log.d("VP", "Clicked folder: ${folder.name} (${folder.videoCount} videos)")
            }
        )
    }
}
