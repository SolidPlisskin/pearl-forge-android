package com.solidplisskin.pearlforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.solidplisskin.pearlforge.theme.PearlForgeTheme
import com.solidplisskin.pearlforge.ui.PearlForgeApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PearlForgeApplication
        setContent { PearlForgeTheme { PearlForgeApp(app.container) } }
    }
}
