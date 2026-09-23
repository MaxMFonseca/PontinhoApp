package com.maxmfonseca.pontinho

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.maxmfonseca.pontinho.ui.PontinhoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PontinhoApplication).container
        setContent {
            PontinhoApp(container)
        }
    }
}
