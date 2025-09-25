package com.example.policyengine.network

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.example.policyengine.MainActivity
import com.example.policyengine.data.BehaviorLog
import com.example.policyengine.data.PolicyResponse
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService {
    companion object {
        private const val BASE_URL = "http://172.21.146.42:8000/api"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .create()

    // 네트워크 데이터 전송
    suspend fun sendNetworkLogs(logs: List<BehaviorLog>): PolicyResponse {
        return withContext(Dispatchers.IO) {
            val requestData = logs.map { log ->
                mapOf(
                    "seq" to log.sequenceIndex,
                    "lat" to (log.params["lat"] as? Double ?: 0.0),
                    "lng" to (log.params["lng"] as? Double ?: 0.0),
                    "net_type" to (log.params["net_type"] as? String ?: "unknown")
                )
            }

            val json = gson.toJson(requestData)
            println("네트워크 JSON: $json")

            val request = Request.Builder()
                .url("$BASE_URL/network-data/")
                .post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("네트워크 데이터 전송 실패: ${response.code}")
            val responseBody = response.body?.string() ?: throw IOException("응답 본문 없음")
            gson.fromJson(responseBody, PolicyResponse::class.java)
        }
    }

    // 센서 데이터 전송
    suspend fun sendSensorLogs(logs: List<BehaviorLog>): PolicyResponse {
        return withContext(Dispatchers.IO) {
            val requestData = logs.map { log ->
                mapOf(
                    "seq" to log.sequenceIndex,
                    "type" to when (log.actionType) {
                        "sensor_accelerometer" -> "accel"
                        "sensor_gyroscope" -> "gyro"
                        else -> "unknown"
                    },
                    "timestamp" to (log.params["timestamp"] as? Long ?: System.currentTimeMillis()),
                    "x" to (log.params["x"] as? Float ?: 0f),
                    "y" to (log.params["y"] as? Float ?: 0f),
                    "z" to (log.params["z"] as? Float ?: 0f)
                )
            }

            val json = gson.toJson(requestData)
            println("센서 JSON: $json")

            val request = Request.Builder()
                .url("$BASE_URL/sensor-data/")
                .post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("센서 데이터 전송 실패: ${response.code}")
            val responseBody = response.body?.string() ?: throw IOException("응답 본문 없음")
            gson.fromJson(responseBody, PolicyResponse::class.java)
        }
    }

    suspend fun sendTouchLogs(logs: List<BehaviorLog>): PolicyResponse {
        return withContext(Dispatchers.IO) {
            val requestData = logs.map { log ->
                val baseData = mutableMapOf<String, Any>(
                    "seq" to log.sequenceIndex,
                    "action_type" to log.actionType,
                    "timestamp" to (log.params["timestamp"] as? Long ?: System.currentTimeMillis())
                )

                when (log.actionType) {
                    "touch_pressure" -> {
                        log.params["x"]?.let { baseData["x"] = it }
                        log.params["y"]?.let { baseData["y"] = it }
                        log.params["size"]?.let { baseData["size"] = it }
                        log.params["touch_duration"]?.let { baseData["touch_duration"] = it }
                        log.params["touch_session"]?.let { baseData["touch_session"] = it }
                    }
                    "drag" -> {
                        log.params.forEach { (key, value) ->
                            if (key != "timestamp") {
                                baseData[key] = value
                            }
                        }
                    }
                }

                baseData
            }

            val json = gson.toJson(requestData)
            println("터치/드래그 JSON 최종: $json")

            val request = Request.Builder()
                .url("$BASE_URL/touch-data/")
                .post(json.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("터치 데이터 전송 실패: ${response.code}")
            val responseBody = response.body?.string() ?: throw IOException("응답 본문 없음")
            gson.fromJson(responseBody, PolicyResponse::class.java)
        }
    }

    // 이상반응 체크 함수
    suspend fun checkAnomalies(sinceId: Int = 0): AnomalyCheckResult {
        return withContext(Dispatchers.IO) {
            val url = if (sinceId > 0) {
                "$BASE_URL/mobile/anomaly/updates?since_id=$sinceId"
            } else {
                "$BASE_URL/mobile/anomaly/updates"
            }

            println("이상반응 체크 요청: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> {
                    val responseBody = response.body?.string() ?: throw IOException("응답 본문 없음")
                    println("이상반응 응답: $responseBody")
                    val anomalyResponse = gson.fromJson(responseBody, AnomalyApiResponse::class.java)
                    AnomalyCheckResult.Success(anomalyResponse)
                }
                204 -> {
                    println("새로운 이상반응 없음")
                    AnomalyCheckResult.NoContent
                }
                else -> {
                    throw IOException("이상반응 조회 실패: ${response.code}")
                }
            }
        }
    }

    // 데이터 클래스들
    data class AnomalyApiResponse(
        @SerializedName("last_id") val lastId: Int,
        val items: List<AnomalyItem>
    )

    data class AnomalyItem(
        val id: Int,
        val modality: String,
        @SerializedName("is_anomaly") val isAnomaly: Boolean,
        @SerializedName("anomaly_score") val anomalyScore: Double,
        val timestamp: String,
        @SerializedName("created_at") val createdAt: String
    )

    sealed class AnomalyCheckResult {
        data class Success(val data: AnomalyApiResponse) : AnomalyCheckResult()
        object NoContent : AnomalyCheckResult()
    }

    // 이상반응 카운터 및 액션 처리 클래스
    class AnomalyManager(private val context: Context) {
        private val prefs = context.getSharedPreferences("anomaly_prefs", Context.MODE_PRIVATE)
        private val apiService = ApiService()
        private var networkDialogShown = false

        // 임계값 상수들
        companion object {
            private const val NETWORK_THRESHOLD = 1      // 네트워크: 1개
            private const val SENSOR_THRESHOLD = 20      // 센서: 20개
            private const val TOUCH_THRESHOLD = 20       // 터치: 20개
        }

        // 카운터 저장
        private var networkCount: Int
            get() = prefs.getInt("network_count", 0)
            set(value) = prefs.edit().putInt("network_count", value).apply()

        private var sensorCount: Int
            get() = prefs.getInt("sensor_count", 0)
            set(value) = prefs.edit().putInt("sensor_count", value).apply()

        private var touchPressureCount: Int
            get() = prefs.getInt("touch_pressure_count", 0)
            set(value) = prefs.edit().putInt("touch_pressure_count", value).apply()

        private var touchDragCount: Int
            get() = prefs.getInt("touch_drag_count", 0)
            set(value) = prefs.edit().putInt("touch_drag_count", value).apply()

        // 마지막 ID 저장
        private var lastAnomalyId: Int
            get() = prefs.getInt("last_anomaly_id", 0)
            set(value) = prefs.edit().putInt("last_anomaly_id", value).apply()

        private var temporarilyDisabled = false
        private var isLockInProgress = false

        suspend fun checkForAnomalies() {
            try {
                val result = apiService.checkAnomalies(lastAnomalyId)

                when (result) {
                    is AnomalyCheckResult.Success -> {
                        handleAnomalies(result.data)
                    }
                    is AnomalyCheckResult.NoContent -> {
                        println("새로운 이상반응 없음")
                    }
                }
            } catch (e: Exception) {
                println("이상반응 조회 실패: ${e.message}")
            }
        }

        private fun handleAnomalies(data: AnomalyApiResponse) {
            lastAnomalyId = data.lastId
            val anomalies = data.items.filter { it.isAnomaly }

            anomalies.forEach { anomaly ->
                when (anomaly.modality) {
                    "network" -> {
                        networkCount++
                        handleAnomalyAction("network", networkCount)
                    }
                    "sensor" -> {
                        sensorCount++
                        handleAnomalyAction("sensor", sensorCount)
                    }
                    "touch_pressure" -> {
                        touchPressureCount++
                        handleAnomalyAction("touch_pressure", touchPressureCount)
                    }
                    "touch_drag" -> {
                        touchDragCount++
                        handleAnomalyAction("touch_drag", touchDragCount)
                    }
                }

                println("이상반응 감지: ${anomaly.modality} (총 ${getCount(anomaly.modality)}개)")

                if (context is MainActivity) {
                    (context as MainActivity).addLog("이상반응 감지: ${anomaly.modality} (총 ${getCount(anomaly.modality)}개)")
                }
            }
        }

        // 이상반응 액션 처리 함수
        private fun handleAnomalyAction(type: String, count: Int) {
            if (temporarilyDisabled) return

            when (type) {
                "network" -> {
                    if (count >= NETWORK_THRESHOLD) {
                        executeNetworkAction(count)
                    }
                }
                "sensor" -> {
                    if (count >= SENSOR_THRESHOLD) {
                        executeLockAction("sensor", count)
                    }
                }
                "touch_pressure", "touch_drag" -> {
                    if (count >= TOUCH_THRESHOLD) {
                        executeLockAction(type, count)
                    }
                }
            }
        }

        // 임시 비활성화 시에도 플래그 리셋
        fun temporaryDisable() {
            temporarilyDisabled = true
            networkDialogShown = false // 복구 시 다시 표시 가능하도록
        }


        // 재활성화 시 플래그는 그대로 유지
        fun reEnable() {
            temporarilyDisabled = false
            // networkDialogShown은 리셋하지 않음 - 한 세션당 한 번만 표시
        }

        // IP 변경과 동일한 다이얼로그를 네트워크 이상반응용으로 생성
        private fun showEnhancedNetworkAnomalyDialogForApiAnomaly(count: Int, isVpnActive: Boolean) {
            val title = if (isVpnActive) "VPN 환경에서 네트워크 이상반응 감지" else "네트워크 이상반응 감지"
            val vpnWarning = if (isVpnActive) "\n\n⚠️ VPN 사용이 감지되었습니다." else ""

            val message = "네트워크 이상반응이 ${count}회 감지되었습니다.${vpnWarning}\n\n이는 보안상 중요한 변화일 수 있습니다.\n어떤 조치를 취하시겠습니까?"

            android.app.AlertDialog.Builder(context)
                .setTitle("🌐 $title")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("비행기 모드 설정") { _, _ ->
                    openAirplaneModeSettings()
                }
                .setNeutralButton("WiFi 설정") { _, _ ->
                    openWiFiSettings()
                }
                .setNegativeButton("무시하고 계속") { _, _ ->
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("사용자가 네트워크 이상반응 경고를 무시함")
                    }
                }
                .show()
        }

        // 네트워크 설정 관련 함수들 추가 (MainActivity에 있는 것들과 동일)
        private fun openAirplaneModeSettings() {
            try {
                val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                } else {
                    Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                }
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("✈️ 비행기 모드 설정 화면으로 이동")
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ 비행기 모드 설정 화면 이동 실패: ${e.message}")
                }
                openWirelessSettings()
            }
        }

        private fun openWiFiSettings() {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("📶 WiFi 설정 화면으로 이동")
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ WiFi 설정 화면 이동 실패: ${e.message}")
                }
                openWirelessSettings()
            }
        }

        private fun openWirelessSettings() {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("⚙️ 무선 네트워크 설정 화면으로 이동")
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ 네트워크 설정 화면 이동 실패: ${e.message}")
                }
            }
        }

        // 개선된 네트워크 이상반응 액션
        private fun executeNetworkAction(count: Int) {
            try {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("네트워크 이상반응 임계값 도달 (${count}개) - 비행기모드 설정 권장")
                }

                // 다이얼로그가 이미 표시되었으면 실행하지 않음
                if (!networkDialogShown) {
                    networkDialogShown = true // 플래그 설정

                    if (context is Activity) {
                        (context as Activity).runOnUiThread {
                            showNetworkSecurityDialog()
                        }
                    } else {
                        enableAirplaneMode()
                    }
                } else {
                    // 이미 다이얼로그가 표시되었음을 로그에 기록
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("네트워크 보안 다이얼로그 이미 표시됨 - 중복 실행 방지")
                    }
                }

            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("네트워크 보안 조치 실행 실패: ${e.message}")
                }
            }
        }

        // VPN 상태 확인 함수 추가 (MainActivity에 있는 것과 동일)
        private fun checkVPNStatus(): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            } else {
                false
            }
        }



        // 네트워크 보안 다이얼로그 (수정된 버전)
        private fun showNetworkSecurityDialog() {
            android.app.AlertDialog.Builder(context)
                .setTitle("🌐 네트워크 보안 경고")
                .setMessage("네트워크 이상반응이 감지되었습니다.\n\n이는 보안상 중요한 변화일 수 있습니다.\n어떤 조치를 취하시겠습니까?")
                .setCancelable(false)
                .setPositiveButton("비행기 모드 설정") { _, _ ->
                    enableAirplaneMode()
                }
                .setNeutralButton("WiFi 설정") { _, _ ->
                    disableWiFi()
                }
                .setNegativeButton("무시하고 계속") { _, _ ->
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("사용자가 네트워크 경고를 무시함")
                    }
                }
                .show()
        }

        // 1. 비행기 모드 설정
        private fun enableAirplaneMode() {
            try {
                val airplaneModeIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }

                airplaneModeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(airplaneModeIntent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("비행기 모드 설정 화면으로 이동")
                }

                android.widget.Toast.makeText(
                    context,
                    "보안을 위해 비행기 모드를 활성화해주세요",
                    android.widget.Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("비행기 모드 설정 화면 이동 실패: ${e.message}")
                }
                openNetworkSettings()
            }
        }

        // 2. WiFi 차단 설정
        private fun disableWiFi() {
            try {
                val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
                wifiIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(wifiIntent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("WiFi 설정 화면으로 이동")
                }

                android.widget.Toast.makeText(
                    context,
                    "보안을 위해 WiFi를 꺼주세요",
                    android.widget.Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("WiFi 설정 화면 이동 실패: ${e.message}")
                }
                openNetworkSettings()
            }
        }

        // 3. 모바일 데이터 차단 설정
        private fun disableMobileData() {
            try {
                val intent = Intent().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        action = Settings.ACTION_DATA_USAGE_SETTINGS
                    } else {
                        action = Settings.ACTION_DATA_ROAMING_SETTINGS
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                context.startActivity(intent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("모바일 데이터 설정 화면으로 이동")
                }

                android.widget.Toast.makeText(
                    context,
                    "보안을 위해 모바일 데이터를 꺼주세요",
                    android.widget.Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("모바일 데이터 설정 화면 이동 실패: ${e.message}")
                }
                openNetworkSettings()
            }
        }

        // 4. 일반 네트워크 설정
        private fun openNetworkSettings() {
            try {
                val networkIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                networkIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(networkIntent)

                if (context is MainActivity) {
                    (context as MainActivity).addLog("무선 네트워크 설정 화면으로 이동")
                }

            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("네트워크 설정 화면 이동 실패: ${e.message}")
                }
            }
        }

        // 센서/터치 이상반응으로 인한 잠금 액션
        private fun executeLockAction(type: String, count: Int) {
            if (isLockInProgress) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("잠금 이미 진행 중 - 중복 실행 방지")
                }
                return
            }

            try {
                isLockInProgress = true

                if (context is MainActivity) {
                    (context as MainActivity).addLog("${type} 이상반응으로 인한 보안 잠금 실행 (${count}개)")
                }

                val securityPrefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
                securityPrefs.edit().clear().apply()

                securityPrefs.edit().apply {
                    putBoolean("anomaly_lock_triggered", true)
                    putString("anomaly_type", type)
                    putLong("lock_timestamp", System.currentTimeMillis())
                    putBoolean("is_anomaly_initiated_lock", true)
                    apply()
                }

                val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = android.content.ComponentName(context, com.example.policyengine.PolicyAdminReceiver::class.java)

                if (devicePolicyManager.isAdminActive(adminComponent)) {
                    devicePolicyManager.lockNow()
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("Device Admin으로 이상반응 잠금 실행")
                    }
                } else {
                    executeAlternativeLockAction(type)
                }

            } catch (e: Exception) {
                executeAlternativeLockAction(type)
            } finally {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isLockInProgress = false
                }, 2000)
            }
        }

        // 대체 잠금 방법
        private fun executeAlternativeLockAction(type: String) {
            try {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("Device Admin 없음 - 대체 보안 조치 실행")
                }

                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(homeIntent)

                if (context is Activity) {
                    (context as Activity).runOnUiThread {
                        showCriticalDialog(
                            "보안 경고",
                            "${type} 이상 행동이 감지되어 보안 조치를 실행합니다.\n앱을 다시 시작하면 복구 모드가 실행됩니다."
                        ) {
                            (context as Activity).finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }
                }

            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("대체 보안 조치 실패: ${e.message}")
                }
            }
        }

        // 경고 다이얼로그 표시
        private fun showCriticalDialog(title: String, message: String, onConfirm: (() -> Unit)? = null) {
            if (context is Activity) {
                try {
                    android.app.AlertDialog.Builder(context)
                        .setTitle(title)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("확인") { dialog, _ ->
                            dialog.dismiss()
                            onConfirm?.invoke()
                        }
                        .show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "$title: $message", android.widget.Toast.LENGTH_LONG).show()
                    onConfirm?.invoke()
                }
            }
        }

        private fun getCount(type: String): Int {
            return when (type) {
                "network" -> networkCount
                "sensor" -> sensorCount
                "touch_pressure" -> touchPressureCount
                "touch_drag" -> touchDragCount
                else -> 0
            }
        }

        fun getAllCounts(): Map<String, Int> {
            return mapOf(
                "network" to networkCount,
                "sensor" to sensorCount,
                "touch_pressure" to touchPressureCount,
                "touch_drag" to touchDragCount
            )
        }
        // 수동으로 플래그 리셋하는 함수 (필요시 사용)
        fun resetNetworkDialogFlag() {
            networkDialogShown = false
            if (context is MainActivity) {
                (context as MainActivity).addLog("네트워크 다이얼로그 플래그 수동 리셋")
            }
        }
        // 카운터 초기화 시 플래그도 리셋
        fun resetCounts() {
            networkCount = 0
            sensorCount = 0
            touchPressureCount = 0
            touchDragCount = 0

            // 다이얼로그 표시 플래그도 리셋
            networkDialogShown = false

            if (context is MainActivity) {
                (context as MainActivity).addLog("이상반응 카운터 및 다이얼로그 플래그 초기화")
            }
        }

        // 임계값 정보 반환
        fun getThresholds(): Map<String, Int> {
            return mapOf(
                "network" to NETWORK_THRESHOLD,
                "sensor" to SENSOR_THRESHOLD,
                "touch_pressure" to TOUCH_THRESHOLD,
                "touch_drag" to TOUCH_THRESHOLD
            )
        }

        // 잠금 상태 리셋
        fun resetLockState() {
            isLockInProgress = false
        }
    }
}