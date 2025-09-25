package com.example.policyengine.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.policyengine.BehaviorDataManager
import com.example.policyengine.R
import com.example.policyengine.collector.RootTouchCollector
import com.example.policyengine.service.OverlayTouchService
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

class BackgroundBehaviorCollectionService : Service(), SensorEventListener {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "behavior_collection_channel"
        private const val WAKELOCK_TAG = "PolicyEngine:BackgroundCollection"

        // 백그라운드에서는 배터리 절약을 위해 더 낮은 주파수
        private const val SENSOR_COLLECTION_INTERVAL = 50L // 20Hz
        private const val NETWORK_COLLECTION_INTERVAL = 5000L // 5초
        private const val PAIR_TIMEOUT_MS = 200L
    }

    // 센서 관련
    private lateinit var sensorManager: SensorManager
    private lateinit var behaviorDataManager: BehaviorDataManager
    private var wakeLock: PowerManager.WakeLock? = null

    // 네트워크 관련
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetworkType = "none"

    // ROOT 터치 수집 관련
    private var rootTouchCollector: RootTouchCollector? = null

    // 코루틴
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 센서 페어링 (기존 로직과 동일)
    data class SensorPair(
        val sequence: Long,
        val timestamp: Long,
        var accelerometer: SensorReading? = null,
        var gyroscope: SensorReading? = null,
        var isComplete: Boolean = false
    )

    data class SensorReading(
        val timestamp: Long,
        val x: Float,
        val y: Float,
        val z: Float
    )

    private val sensorPairBuffer = ConcurrentLinkedQueue<SensorPair>()
    private val pairSequenceCounter = AtomicLong(0L)
    private val networkSequenceCounter = AtomicLong(0L)
    private var currentPair: SensorPair? = null

    private var lastAccelerometerTime = 0L
    private var lastGyroscopeTime = 0L

    override fun onCreate() {
        super.onCreate()

        // BehaviorDataManager 초기화 (이미 초기화되어 있을 수 있음)
        try {
            behaviorDataManager = BehaviorDataManager.getInstance()
        } catch (e: Exception) {
            BehaviorDataManager.initialize(this)
            behaviorDataManager = BehaviorDataManager.getInstance()
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startBackgroundCollection()
        return START_STICKY // 시스템에 의해 종료되어도 재시작
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Behavior Collection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 사용자 행동 데이터를 수집합니다"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Policy Engine 백그라운드 수집")
            .setContentText("센서 및 네트워크 데이터를 수집하고 있습니다")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            acquire(10 * 60 * 1000L) // 10분 - 주기적으로 갱신
        }
    }

    private fun startBackgroundCollection() {
        // 센서 수집 시작
        startSensorCollection()

        // 네트워크 수집 시작
        startNetworkCollection()

        // 터치 수집 시작 (ROOT 우선, 실패시 오버레이)
        startTouchCollection()

        // 배치 전송 시작
        startBatchTransmission()

        // WakeLock 갱신
        startWakeLockRefresh()
    }

    private fun startTouchCollection() {
        // ROOT 터치 수집 시도
        try {
            rootTouchCollector = RootTouchCollector(this)
            rootTouchCollector?.startRootTouchCollection()
        } catch (e: Exception) {
            // ROOT 실패시 오버레이 터치 수집 시도
            startOverlayTouchCollection()
        }
    }

    private fun startOverlayTouchCollection() {
        try {
            if (OverlayTouchService.hasOverlayPermission(this)) {
                val overlayIntent = Intent(this, OverlayTouchService::class.java)
                startService(overlayIntent)
            }
        } catch (e: Exception) {
            // 오버레이도 실패하면 터치 수집 포기
        }
    }

    private fun startSensorCollection() {
        // 가속도계 등록
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // 자이로스코프 등록
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun startNetworkCollection() {
        serviceScope.launch {
            while (true) {
                try {
                    updateNetworkType()
                    sendNetworkData()
                    delay(NETWORK_COLLECTION_INTERVAL)
                } catch (e: Exception) {
                    // 에러 무시하고 계속 수집
                    delay(NETWORK_COLLECTION_INTERVAL)
                }
            }
        }
    }

    private fun updateNetworkType() {
        currentNetworkType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            when {
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> "none"
            }
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            when (activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "cellular"
                else -> "none"
            }
        }
    }

    private fun sendNetworkData() {
        val data = mapOf(
            "seq" to networkSequenceCounter.incrementAndGet(),
            "lat" to 0.0, // 백그라운드에서는 위치 수집하지 않음 (권한/배터리 이슈)
            "lng" to 0.0,
            "net_type" to currentNetworkType,
            "background_collection" to true // 백그라운드 수집임을 표시
        )

        behaviorDataManager.createAndAddLog("network_status", data)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { sensorEvent ->
            val currentTime = System.currentTimeMillis()

            when (sensorEvent.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    if (currentTime - lastAccelerometerTime >= SENSOR_COLLECTION_INTERVAL) {
                        val reading = SensorReading(
                            timestamp = currentTime,
                            x = sensorEvent.values[0],
                            y = sensorEvent.values[1],
                            z = sensorEvent.values[2]
                        )
                        addAccelerometerReading(reading)
                        lastAccelerometerTime = currentTime
                    }
                }

                Sensor.TYPE_GYROSCOPE -> {
                    if (currentTime - lastGyroscopeTime >= SENSOR_COLLECTION_INTERVAL) {
                        val reading = SensorReading(
                            timestamp = currentTime,
                            x = sensorEvent.values[0],
                            y = sensorEvent.values[1],
                            z = sensorEvent.values[2]
                        )
                        addGyroscopeReading(reading)
                        lastGyroscopeTime = currentTime
                    }
                }
            }
        }
    }

    // 기존 MainActivity의 센서 페어링 로직과 동일
    private fun addAccelerometerReading(reading: SensorReading) {
        synchronized(this) {
            if (currentPair == null) {
                currentPair = SensorPair(
                    sequence = pairSequenceCounter.incrementAndGet(),
                    timestamp = reading.timestamp,
                    accelerometer = reading
                )
            } else {
                val pair = currentPair!!
                if (pair.accelerometer == null &&
                    reading.timestamp - pair.timestamp <= PAIR_TIMEOUT_MS) {
                    pair.accelerometer = reading
                    checkPairCompletion(pair)
                } else {
                    finalizePairIfNeeded(pair)
                    currentPair = SensorPair(
                        sequence = pairSequenceCounter.incrementAndGet(),
                        timestamp = reading.timestamp,
                        accelerometer = reading
                    )
                }
            }
        }
    }

    private fun addGyroscopeReading(reading: SensorReading) {
        synchronized(this) {
            if (currentPair == null) {
                currentPair = SensorPair(
                    sequence = pairSequenceCounter.incrementAndGet(),
                    timestamp = reading.timestamp,
                    gyroscope = reading
                )
            } else {
                val pair = currentPair!!
                if (pair.gyroscope == null &&
                    reading.timestamp - pair.timestamp <= PAIR_TIMEOUT_MS) {
                    pair.gyroscope = reading
                    checkPairCompletion(pair)
                } else {
                    finalizePairIfNeeded(pair)
                    currentPair = SensorPair(
                        sequence = pairSequenceCounter.incrementAndGet(),
                        timestamp = reading.timestamp,
                        gyroscope = reading
                    )
                }
            }
        }
    }

    private fun checkPairCompletion(pair: SensorPair) {
        if (pair.accelerometer != null && pair.gyroscope != null && !pair.isComplete) {
            pair.isComplete = true
            sensorPairBuffer.offer(pair)
            currentPair = null
        }
    }

    private fun finalizePairIfNeeded(pair: SensorPair) {
        if (!pair.isComplete) {
            sensorPairBuffer.offer(pair)
        }
    }

    private fun startBatchTransmission() {
        serviceScope.launch {
            while (true) {
                delay(2000) // 백그라운드에서는 2초마다 전송

                try {
                    val completePairs = mutableListOf<SensorPair>()
                    while (sensorPairBuffer.isNotEmpty()) {
                        sensorPairBuffer.poll()?.let { completePairs.add(it) }
                    }

                    synchronized(this@BackgroundBehaviorCollectionService) {
                        currentPair?.let { pair ->
                            if (System.currentTimeMillis() - pair.timestamp > PAIR_TIMEOUT_MS) {
                                finalizePairIfNeeded(pair)
                                completePairs.add(pair)
                                currentPair = null
                            }
                        }
                    }

                    if (completePairs.isNotEmpty()) {
                        transmitSensorPairs(completePairs)
                    }
                } catch (e: Exception) {
                    // 에러 무시하고 계속 전송
                }
            }
        }
    }

    private fun transmitSensorPairs(pairs: List<SensorPair>) {
        pairs.forEach { pair ->
            pair.gyroscope?.let { gyro ->
                val gyroParams = mapOf(
                    "timestamp" to gyro.timestamp,
                    "x" to gyro.x,
                    "y" to gyro.y,
                    "z" to gyro.z,
                    "magnitude" to sqrt((gyro.x * gyro.x + gyro.y * gyro.y + gyro.z * gyro.z).toDouble()),
                    "sampling_rate" to "20Hz",
                    "batch_transmission" to true,
                    "sensor_name" to "gyroscope",
                    "real_data" to true,
                    "paired" to true,
                    "background_collection" to true // 백그라운드 수집임을 표시
                )
                behaviorDataManager.createAndAddLogWithSequence("sensor_gyroscope", gyroParams, pair.sequence)
            }

            pair.accelerometer?.let { accel ->
                val accelParams = mapOf(
                    "timestamp" to accel.timestamp,
                    "x" to accel.x,
                    "y" to accel.y,
                    "z" to accel.z,
                    "magnitude" to sqrt((accel.x * accel.x + accel.y * accel.y + accel.z * accel.z).toDouble()),
                    "sampling_rate" to "20Hz",
                    "batch_transmission" to true,
                    "sensor_name" to "accelerometer",
                    "real_data" to true,
                    "paired" to true,
                    "background_collection" to true // 백그라운드 수집임을 표시
                )
                behaviorDataManager.createAndAddLogWithSequence("sensor_accelerometer", accelParams, pair.sequence)
            }
        }
    }

    private fun startWakeLockRefresh() {
        serviceScope.launch {
            while (true) {
                delay(8 * 60 * 1000L) // 8분마다 갱신
                try {
                    wakeLock?.let {
                        if (it.isHeld) {
                            it.release()
                        }
                        it.acquire(10 * 60 * 1000L)
                    }
                } catch (e: Exception) {
                    // WakeLock 갱신 실패는 무시
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 백그라운드에서는 센서 정확도 변경 이벤트를 로깅하지 않음 (배터리 절약)
    }

    override fun onDestroy() {
        super.onDestroy()

        // 센서 해제
        sensorManager.unregisterListener(this)

        // ROOT 터치 수집 중지
        rootTouchCollector?.stopRootTouchCollection()

        // 코루틴 취소
        serviceScope.cancel()

        // WakeLock 해제
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        // 버퍼 정리
        sensorPairBuffer.clear()
        currentPair = null
    }
}