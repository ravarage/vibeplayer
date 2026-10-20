package com.vibeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vibeplayer.data.media.VideoItem
import com.vibeplayer.ui.browser.BrowserRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.vibeplayer.ui.theme.VibeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserPermissionWrapper {
                        BrowserRoot(
                            onPlayVideo = { item: VideoItem ->
                                val uri = android.net.Uri.fromFile(java.io.File(item.path))
                                Log.i("VP", "Starting ACTION_VIEW intent for Uri: $uri")
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/*")
                                    setClassName(packageName, "com.vibeplayer.ui.player.PlayerActivity")
                                }
                                try {
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Log.w("VP", "PlayerActivity not found or failed to start for $uri: ${e.message}")
                                    Toast.makeText(this@MainActivity, "Player not available: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BrowserPermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            Log.d("VP", "READ_MEDIA_VIDEO permission granted by user")
        } else {
            Log.w("VP", "READ_MEDIA_VIDEO permission denied by user")
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            launcher.launch(permission)
        }
    }

    if (hasPermission) {
        content()
    } else {
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
    }
}
