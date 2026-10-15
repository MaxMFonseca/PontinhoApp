package com.maxmfonseca.pontinho

import android.app.Application
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.di.DefaultAppContainer
import com.maxmfonseca.pontinho.ui.scanner.deleteStaleScanCaptures

class PontinhoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        deleteStaleScanCaptures(cacheDir)
        container = DefaultAppContainer(applicationContext)
    }
}
