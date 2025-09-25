package com.example.policyengine.handler

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.policyengine.MainActivity
import com.example.policyengine.R
import com.example.policyengine.data.PolicyResponse

class PolicyResponseHandler(private val context: Context) {

    fun handleResponse(response: PolicyResponse) {
        when (response.decision) {
            "allow" -> {
                // 정상 동작 - 로그만 기록
                showToast("✅ 정상 행동으로 판단됨 (위험도: ${String.format("%.2f", response.riskScore)})")
            }

            "warning" -> {
                showWarning(response.message, response.riskScore)
            }

            "additional_auth" -> {
                requestAdditionalAuth(response.message)
            }

            "block" -> {
                blockAccess(response.message)
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun showWarning(message: String, riskScore: Float) {
        AlertDialog.Builder(context)
            .setTitle("⚠️ 보안 경고")
            .setMessage("$message\n\n위험도: ${String.format("%.1f%%", riskScore * 100)}")
            .setPositiveButton("확인", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun requestAdditionalAuth(message: String) {
        AlertDialog.Builder(context)
            .setTitle("🔐 추가 인증 필요")
            .setMessage(message)
            .setPositiveButton("인증하기") { _, _ ->
                // 추가 인증 로직 (생체인증, PIN 등)
                startAdditionalAuth()
            }
            .setNegativeButton("취소") { _, _ ->
                showToast("인증이 취소되었습니다")
            }
            .setCancelable(false)
            .show()
    }

    private fun blockAccess(message: String) {
        AlertDialog.Builder(context)
            .setTitle("🚫 접근 차단")
            .setMessage(message)
            .setPositiveButton("확인") { _, _ ->
                // 앱 종료 또는 제한된 기능으로 전환
                if (context is MainActivity) {
                    (context as MainActivity).finish()
                }
            }
            .setCancelable(false)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun startAdditionalAuth() {
        // 생체인증이나 PIN 입력 화면으로 이동
        // 실제 구현시에는 BiometricPrompt API 사용
        showToast("추가 인증 기능은 추후 구현 예정")
    }
}