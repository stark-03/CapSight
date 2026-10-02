package com.example.capsight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.capsight.ui.debug.DebugScreen
import com.example.capsight.ui.theme.CapSightTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CapSightTheme {
                // Build step 1: the camera debug screen is the whole app for now.
                // The accessible main screen arrives in step 7 (FR-APP-01).
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DebugScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
