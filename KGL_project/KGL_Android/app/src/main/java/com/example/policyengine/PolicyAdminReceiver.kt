// PolicyAdminReceiver.kt 파일 생성
package com.example.policyengine

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class PolicyAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device Admin 활성화됨
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Device Admin 비활성화됨
    }
}