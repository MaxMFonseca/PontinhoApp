package com.maxmfonseca.pontinho

import android.app.Application
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.di.DefaultAppContainer

class PontinhoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
    }
}
