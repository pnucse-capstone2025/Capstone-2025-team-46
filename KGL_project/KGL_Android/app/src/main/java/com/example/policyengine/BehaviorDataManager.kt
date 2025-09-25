package com.example.policyengine

import android.content.Context
import com.example.policyengine.data.*
import com.example.policyengine.network.ApiService
import com.example.policyengine.util.DeviceUtils
import com.example.policyengine.util.LocationUtils
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

//BehaviorDataManager - 시작/중지 스위치 추가
class BehaviorDataManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: BehaviorDataManager? = null

        fun initialize(context: Context) {
            if (INSTANCE == null) {
                synchronized(this) {
                    if (INSTANCE == null) {
                        INSTANCE = BehaviorDataManager(context.applicationContext)
                    }
                }
            }
        }

        fun getInstance(): BehaviorDataManager {
            return INSTANCE ?: throw IllegalStateException("BehaviorDataManager not initialized")
        }
    }

    // 핵심 컴포넌트들
    private val behaviorQueue = ConcurrentLinkedQueue<BehaviorLog>()
    private val apiService = ApiService()
    private val locationUtils = LocationUtils(context)

    // 시퀀스 관리
    private val sequenceCounter = AtomicLong(0L)

    // 기본 세션 정보
    val userId: String by lazy { generateUserId() }
    val sessionId: String by lazy { generateSessionId() }
    val deviceInfo: DeviceInfo by lazy { collectDeviceInfo() }
    val sessionStartTime = System.currentTimeMillis()

    // 코루틴 스코프
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 상태 추적
    private var lastTransmissionTime = "시작 전"

    //  전송 on/off
    @Volatile private var transmissionEnabled = false
    fun setTransmissionEnabled(enabled: Boolean) { transmissionEnabled = enabled }
    fun isTransmissionEnabled(): Boolean = transmissionEnabled

    init {
        startCategorizedTransmission()
    }

    fun getCurrentLocation(): LocationInfo? {
        return locationUtils.getCurrentLocation()?.let {
            LocationInfo(latitude = it.latitude, longitude = it.longitude, accuracy = it.accuracy)
        }
    }

    fun createAndAddLogWithSequence(actionType: String, params: Map<String, Any>, sequence: Long) {
        val log = BehaviorLog(
            timestamp = getCurrentISO8601(),
            userId = userId,
            sessionId = sessionId,
            sequenceIndex = sequence,
            actionType = actionType,
            params = params,
            deviceInfo = deviceInfo,
            location = getCurrentLocation()
        )
        addBehaviorLog(log)
    }

    //  터치 즉시 전송: 전송 off면 큐에 보관
    fun createAndAddLogForImmediateTransmission(actionType: String, params: Map<String, Any>) {
        val log = BehaviorLog(
            timestamp = getCurrentISO8601(),
            userId = userId,
            sessionId = sessionId,
            sequenceIndex = sequenceCounter.incrementAndGet(),
            actionType = actionType,
            params = params,
            deviceInfo = deviceInfo,
            location = getCurrentLocation()
        )

        coroutineScope.launch {
            try {
                if (transmissionEnabled) {
                    apiService.sendTouchLogs(listOf(log))
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("✅ 터치 데이터 전송 성공: $actionType")
                    }
                } else {
                    behaviorQueue.offer(log)
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("⏸ 전송 비활성: 터치 로그를 큐에 저장")
                    }
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ 터치 데이터 전송 실패: ${e.message}")
                }
                behaviorQueue.offer(log)
            }
        }
    }

    fun createAndAddLog(actionType: String, params: Map<String, Any>) {
        val log = BehaviorLog(
            timestamp = getCurrentISO8601(),
            userId = userId,
            sessionId = sessionId,
            sequenceIndex = sequenceCounter.incrementAndGet(),
            actionType = actionType,
            params = params,
            deviceInfo = deviceInfo,
            location = getCurrentLocation()
        )
        addBehaviorLog(log)
    }

    fun addBehaviorLog(log: BehaviorLog) {
        behaviorQueue.offer(log)
        while (behaviorQueue.size > 200) {
            behaviorQueue.poll()
        }
    }

    // 1초 주기 루프: 전송 off면 skip
    private fun startCategorizedTransmission() {
        coroutineScope.launch {
            while (true) {
                delay(1000)
                if (!transmissionEnabled) continue
                transmitCategorizedData()
            }
        }
    }

    private fun transmitCategorizedData() {
        if (behaviorQueue.isEmpty()) return

        coroutineScope.launch {
            try {
                val allLogs = behaviorQueue.toList()
                val categorizedLogs = categorizeLogsByType(allLogs)
                var totalTransmitted = 0

                //  네트워크
                val networkLogs = categorizedLogs["network"] ?: emptyList()
                if (networkLogs.isNotEmpty()) {
                    try {
                        apiService.sendNetworkLogs(networkLogs)
                        totalTransmitted += networkLogs.size
                        if (context is MainActivity) {
                            (context as MainActivity).addLog("🌐 네트워크 ${networkLogs.size}개 전송")
                        }
                    } catch (e: Exception) {
                        if (context is MainActivity) {
                            (context as MainActivity).addLog("❌ 네트워크 전송 실패: ${e.message}")
                        }
                    }
                }

                // 센서
                val sensorLogs = categorizedLogs["sensor"] ?: emptyList()
                if (sensorLogs.isNotEmpty()) {
                    try {
                        apiService.sendSensorLogs(sensorLogs)
                        totalTransmitted += sensorLogs.size
                        if (context is MainActivity) {
                            (context as MainActivity).addLog("🔬 센서 ${sensorLogs.size}개 전송")
                        }
                    } catch (_: Exception) { /* 로그만 */ }
                }

                if (totalTransmitted > 0) {
                    behaviorQueue.clear()
                    lastTransmissionTime = getCurrentTimeString()
                }
            } catch (_: Exception) { /* 로그만 */ }
        }
    }

    // 카테고리 매핑
    private fun categorizeLogsByType(logs: List<BehaviorLog>): Map<String, List<BehaviorLog>> {
        return logs.groupBy { log ->
            when (log.actionType) {
                "network_status", "location_update" -> "network"
                "sensor_accelerometer", "sensor_gyroscope" -> "sensor"
                // touch는 즉시 전송으로 처리하므로 배치 전송에서 제외
                else -> "ignore"
            }
        }.filterKeys { it != "ignore" }
    }

    suspend fun forceSendData() {
        transmitCategorizedData()
    }

    fun clearAllData() {
        behaviorQueue.clear()
        sequenceCounter.set(0L)
        lastTransmissionTime = "초기화됨"
        if (context is MainActivity) {
            (context as MainActivity).addLog("🗑️ 데이터 초기화")
        }
    }

    // === 헬퍼 ===
    private fun generateUserId(): String {
        val deviceId = DeviceUtils.getDeviceFingerprint(context)
        return "device-${deviceId.hashCode()}"
    }

    private fun generateSessionId(): String = "session-${UUID.randomUUID()}"

    private fun collectDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            appVersion = DeviceUtils.getAppVersion(context),
            screenDensity = context.resources.displayMetrics.density,
            networkType = DeviceUtils.getNetworkType(context)
        )
    }

    private fun getCurrentTimeString(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date())
    }

    private fun getCurrentISO8601(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }
}
