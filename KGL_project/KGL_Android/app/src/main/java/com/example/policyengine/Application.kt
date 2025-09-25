package com.example.policyengine

import android.app.Application


class PolicyEngineApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        BehaviorDataManager.initialize(this)
    }
}