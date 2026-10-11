package com.vibeplayer

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vibeplayer.player.MpvNative

class MainActivity : ComponentActivity() {
    private var mpvHandle: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mpvHandle = MpvNative.create()
        if (mpvHandle != 0L) {
            MpvNative.setOptionString(mpvHandle, "vo", "null")
            MpvNative.setOptionString(mpvHandle, "ao", "null")
            val res = MpvNative.initialize(mpvHandle)
            Log.i("VP", "MpvNative.initialize returned $res")
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mpvHandle != 0L) {
            MpvNative.destroy(mpvHandle)
            mpvHandle = 0
            Log.i("VP", "MpvNative destroyed")
        }
    }
}
