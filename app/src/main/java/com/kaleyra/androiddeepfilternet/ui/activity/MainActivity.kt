package com.kaleyra.androiddeepfilternet.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kaleyra.androiddeepfilternet.ui.screen.NoiseFilterDemoScreen
import com.kaleyra.androiddeepfilternet.ui.theme.AndroidDeepFilterNetTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidDeepFilterNetTheme {
                NoiseFilterDemoScreen()
            }
        }
    }
}


