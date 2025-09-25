package com.example.policyengine

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.policyengine.service.BackgroundBehaviorCollectionService

//  자동 백그라운드 수집을 위한 PolicyEngineApplication
class PolicyEngineApplication : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super.onCreate()

        // BehaviorDataManager 초기화
        BehaviorDataManager.initialize(this)

        // 앱 생명주기 관찰 시작
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // 앱이 포그라운드로 올 때
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // 백그라운드 서비스 중지 (MainActivity에서 수집)
        stopBackgroundService()
    }

    // 앱이 백그라운드로 갈 때
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // 백그라운드 서비스 시작
        startBackgroundService()
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundBehaviorCollectionService::class.java)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // 에러 무시
        }
    }

    private fun stopBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundBehaviorCollectionService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            // 에러 무시
        }
    }
}