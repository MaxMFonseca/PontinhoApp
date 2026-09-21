package com.maxmfonseca.pontinho

import android.app.Application
import com.maxmfonseca.pontinho.di.AppContainer

class PontinhoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
