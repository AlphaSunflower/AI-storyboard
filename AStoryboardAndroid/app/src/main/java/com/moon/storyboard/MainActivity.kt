package com.moon.storyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moon.storyboard.ui.AppRoot
import com.moon.storyboard.ui.theme.MoonTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as StoryboardApp
        setContent {
            MoonTheme {
                AppRoot(app)
            }
        }
    }
}
