// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app

import android.app.Application
import org.knightstalker.app.di.AppModule

class KnightStalkerApp : Application() {

    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)
    }
}
