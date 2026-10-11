package com.vibeplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.vibeplayer.ui.player.PlayerActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, PlayerActivity::class.java).apply {
            action = intent.action
            data = intent.data
            type = intent.type
            intent.extras?.let { putExtras(it) }
        }
        startActivity(target)
        finish()
    }
}
