package com.resonar.app

import android.app.Application
import com.resonar.app.di.AppContainer

/** Application liviana: solo crea el contenedor de dependencias. */
class ResonarApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
