package com.btl.protocol.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import com.btl.protocol.ui.screens.EmergencyDashboardScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContent {
            EmergencyDashboardScreen()
        }
    }
}
