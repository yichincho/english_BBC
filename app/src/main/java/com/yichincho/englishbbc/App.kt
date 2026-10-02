package com.yichincho.englishbbc

import android.app.Application
import com.yichincho.englishbbc.data.Repo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
