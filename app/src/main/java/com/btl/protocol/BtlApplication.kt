package com.btl.protocol

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BtlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            System.loadLibrary("btl_crypto")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }
}
