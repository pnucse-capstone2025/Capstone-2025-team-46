package com.example.policyengine.collector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.policyengine.BehaviorDataManager
import com.example.policyengine.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

// 페어링 기반 SensorDataCollector - accel + gyro
class SensorDataCollector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val behaviorDataManager = BehaviorDataManager.getInstance()

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
    private var currentPair: SensorPair? = null

    private var lastAccelerometerTime = 0L
    private var lastGyroscopeTime = 0L
    private val SENSOR_COLLECTION_INTERVAL = 100L // 50Hz

    private val PAIR_TIMEOUT_MS = 100L
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var totalAccelerometerSamples = 0
    private var totalGyroscopeSamples = 0
    private var totalCompletePairs = 0
    private var totalBatchesSent = 0

    fun startSensorCollection() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            val success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            if (context is MainActivity) {
                if (success) (context as MainActivity).addLog("📡 가속도계 50Hz 수집 시작")
                else (context as MainActivity).addLog("❌ 가속도계 등록 실패")
            }
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensor ->
            val success = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            if (context is MainActivity) {
                if (success) (context as MainActivity).addLog("🌀 자이로스코프 50Hz 수집 시작")
                else (context as MainActivity).addLog("❌ 자이로스코프 등록 실패")
            }
        }

        startBatchTransmission()
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
                        totalAccelerometerSamples++
                        if (context is MainActivity) {
                            (context as MainActivity).incrementEventCount("sensor_accelerometer")
                        }
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
                        totalGyroscopeSamples++
                        if (context is MainActivity) {
                            (context as MainActivity).incrementEventCount("sensor_gyroscope")
                        }
                        lastGyroscopeTime = currentTime
                    }
                }
            }
        }
    }

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
            totalCompletePairs++
            if (context is MainActivity) {
                (context as MainActivity).addLog("✅ 센서 쌍 완성: seq=${pair.sequence}")
            }
            currentPair = null
        }
    }

    private fun finalizePairIfNeeded(pair: SensorPair) {
        if (!pair.isComplete) {
            sensorPairBuffer.offer(pair)
            if (context is MainActivity) {
                val status = when {
                    pair.accelerometer != null && pair.gyroscope == null -> "accel만"
                    pair.gyroscope != null && pair.accelerometer == null -> "gyro만"
                    else -> "빈쌍"
                }
                (context as MainActivity).addLog("⚠️ 미완성 쌍: seq=${pair.sequence} ($status)")
            }
        }
    }

    private fun startBatchTransmission() {
        coroutineScope.launch {
            while (true) {
                delay(1000)

                try {
                    val completePairs = mutableListOf<SensorPair>()
                    while (sensorPairBuffer.isNotEmpty()) {
                        sensorPairBuffer.poll()?.let { completePairs.add(it) }
                    }

                    synchronized(this@SensorDataCollector) {
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
                        totalBatchesSent++
                        if (context is MainActivity) {
                            val completeCount = completePairs.count { it.isComplete }
                            (context as MainActivity).onDataTransmitted(completePairs.size * 2, "센서쌍")
                            (context as MainActivity).addLog("📊 센서쌍 배치 전송: ${completePairs.size}쌍 (완성: $completeCount)")
                        }
                    }
                } catch (e: Exception) {
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("❌ 센서쌍 배치 전송 실패: ${e.message}")
                    }
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
                    "sampling_rate" to "50Hz",
                    "batch_transmission" to true,
                    "sensor_name" to "gyroscope",
                    "real_data" to true,
                    "paired" to true
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
                    "sampling_rate" to "50Hz",
                    "batch_transmission" to true,
                    "sensor_name" to "accelerometer",
                    "real_data" to true,
                    "paired" to true
                )
                behaviorDataManager.createAndAddLogWithSequence("sensor_accelerometer", accelParams, pair.sequence)
            }
        }

        if (context is MainActivity) {
            (context as MainActivity).updateQueueSize(pairs.size * 2)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (context is MainActivity) {
            (context as MainActivity).incrementEventCount("sensor_calibration")
            (context as MainActivity).addLog("📡 센서 정확도 변경: ${sensor?.name} → $accuracy")
        }

        behaviorDataManager.createAndAddLog("sensor_calibration", mapOf(
            "sensor_type" to (sensor?.type ?: -1),
            "sensor_name" to (sensor?.name ?: "unknown"),
            "accuracy_level" to accuracy,
            "accuracy_description" to when (accuracy) {
                0 -> "unreliable"
                1 -> "low"
                2 -> "medium"
                3 -> "high"
                else -> "unknown"
            },
            "timestamp" to System.currentTimeMillis(),
            "real_event" to true,
            "paired_sequence" to true
        ))
    }

    fun getSensorStats(): Map<String, Any> {
        return mapOf(
            "total_accelerometer_samples" to totalAccelerometerSamples,
            "total_gyroscope_samples" to totalGyroscopeSamples,
            "total_complete_pairs" to totalCompletePairs,
            "total_batches_sent" to totalBatchesSent,
            "current_pair_buffer" to sensorPairBuffer.size,
            "pair_sequence_count" to pairSequenceCounter.get(),
            "pair_timeout_ms" to PAIR_TIMEOUT_MS,
            "is_collecting" to (totalAccelerometerSamples > 0 || totalGyroscopeSamples > 0)
        )
    }

    fun stopSensorCollection() {
        sensorManager.unregisterListener(this)
        coroutineScope.cancel()
        sensorPairBuffer.clear()
        currentPair = null
        if (context is MainActivity) {
            (context as MainActivity).addLog("🛑 센서쌍 수집 중지 (총 완성쌍: $totalCompletePairs, 시퀀스: ${pairSequenceCounter.get()})")
        }
    }
}
