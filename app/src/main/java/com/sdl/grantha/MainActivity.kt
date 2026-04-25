package com.sdl.grantha

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sdl.grantha.ui.navigation.NavGraph
import com.sdl.grantha.ui.theme.SanskritDigitalLibraryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SanskritDigitalLibraryTheme {
                NavGraph()
            }
        }
    }
}
