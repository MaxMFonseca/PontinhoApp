package com.maxmfonseca.pontinho

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.maxmfonseca.pontinho.ui.PontinhoApp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PontinhoApplication).container
        setContent {
            PontinhoApp(container)
        }
    }
}
