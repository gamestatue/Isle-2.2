package com.example.isle

import android.app.Application

class IsleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}
