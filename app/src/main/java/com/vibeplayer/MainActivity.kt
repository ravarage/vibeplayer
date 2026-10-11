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

            val retMediacodec = MpvNative.setOptionString(mpvHandle, "hwdec", "mediacodec")
            val retMediacodecCopy = MpvNative.setOptionString(mpvHandle, "hwdec", "mediacodec-copy")
            Log.i("VP", "P0-09-HWDEC-TEST: mediacodec=$retMediacodec, mediacodec-copy=$retMediacodecCopy")

            for (opt in listOf("vo", "hwdec", "gpu-context", "gpu-api", "ao")) {
                val choices = MpvNative.getPropertyString(mpvHandle, "option-info/$opt/choices")
                Log.i("VP", "P0-09-CHOICES-$opt: $choices")
            }

            val decodersJson = MpvNative.getPropertyString(mpvHandle, "decoder-list") ?: "[]"
            val decodersArray = org.json.JSONArray(decodersJson)
            val matchedDecoders = mutableMapOf<String, MutableList<String>>()
            val targets = listOf("dav1d", "mediacodec", "h264", "hevc", "vp9", "av1", "ac3", "eac3", "dts", "truehd", "aac", "flac", "opus", "vorbis", "mp3", "ass", "subrip", "pgssub")
            for (t in targets) matchedDecoders[t] = mutableListOf()

            for (i in 0 until decodersArray.length()) {
                val obj = decodersArray.getJSONObject(i)
                val codec = obj.optString("codec")
                val driver = obj.optString("driver")
                val entry = "$codec ($driver)"
                for (t in targets) {
                    if (codec.contains(t, ignoreCase = true) || driver.contains(t, ignoreCase = true)) {
                        matchedDecoders[t]?.add(entry)
                    }
                }
            }
            Log.i("VP", "P0-09-DECODERS-TOTAL: ${decodersArray.length()}")
            for ((k, v) in matchedDecoders) {
                Log.i("VP", "P0-09-DECODER-$k: [${v.size}] ${v.joinToString()}")
            }

            val demuxers = MpvNative.getPropertyString(mpvHandle, "demuxer-lavf-list") ?: ""
            Log.i("VP", "P0-09-DEMUXERS-TOTAL: ${demuxers.split(',').size}")

            val encoders = MpvNative.getPropertyString(mpvHandle, "encoder-list") ?: "[]"
            Log.i("VP", "P0-09-ENCODERS: $encoders")
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
