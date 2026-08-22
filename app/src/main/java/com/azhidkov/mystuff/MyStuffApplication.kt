package com.azhidkov.mystuff

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck

class MyStuffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        Firebase.appCheck.installAppCheckProviderFactory(appCheckProviderFactory())
    }
}
