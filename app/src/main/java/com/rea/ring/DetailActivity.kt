package com.rea.ring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

class DetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("key") ?: "heart"
        val mi = METRICS.firstOrNull { it.key == key } ?: METRICS[1]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = CardBg)) {
                Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
                    if (key == "sleep") SleepScreen { finish() } else DetailScreen(mi) { finish() }
                }
            }
        }
    }
}
