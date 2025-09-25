package com.example.policyengine.collector

import android.content.Context
import com.example.policyengine.BehaviorDataManager
import com.example.policyengine.MainActivity
import kotlinx.coroutines.*
import java.io.*
import kotlin.math.abs
import kotlin.math.sqrt

class RootTouchCollector(private val context: Context) {

    private val behaviorDataManager = BehaviorDataManager.getInstance()
    private var coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 터치 이벤트 처리 (기존 TouchEventCollector와 동일한 로직)
    private val DRAG_THRESHOLD = context.resources.displayMetrics.density * 6 // 6dp

    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartTime = 0L

    private var totalMoveEvents = 0
    private var maxDistanceMoved = 0f
    private var allPositions = mutableListOf<Pair<Float, Float>>()

    private var currentTouchSession = 0L
    private var touchSessionStarted = false

    // ROOT 권한 관련
    private var inputProcess: Process? = null
    private var inputReader: BufferedReader? = null
    private var isCollecting = false

    // 터치 이벤트 파싱을 위한 상수들
    companion object {
        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03

        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_PRESSURE = 0x18
        private const val ABS_MT_TOUCH_MAJOR = 0x30
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_PRESSURE = 0x3a

        private const val BTN_TOUCH = 0x14a

        private const val SYN_REPORT = 0x00
    }

    // 현재 터치 상태 추적
    private var currentX = 0f
    private var currentY = 0f
    private var currentPressure = 0f
    private var currentSize = 0f
    private var isTouchDown = false

    fun startRootTouchCollection() {
        if (isCollecting) return

        coroutineScope.launch {
            try {
                if (checkRootAccess()) {
                    startInputEventMonitoring()
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("🔓 ROOT 터치 수집 시작")
                    }
                } else {
                    if (context is MainActivity) {
                        (context as MainActivity).addLog("❌ ROOT 권한 없음")
                    }
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ ROOT 터치 수집 실패: ${e.message}")
                }
            }
        }
    }

    private suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su -c 'echo test'")
            val result = process.waitFor()
            result == 0
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun startInputEventMonitoring() = withContext(Dispatchers.IO) {
        try {
            // 터치 입력 디바이스 찾기
            val touchDevice = findTouchDevice()
            if (touchDevice.isEmpty()) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ 터치 디바이스를 찾을 수 없음")
                }
                return@withContext
            }

            if (context is MainActivity) {
                (context as MainActivity).addLog("📱 터치 디바이스: $touchDevice")
            }

            // getevent 명령으로 터치 이벤트 모니터링
            inputProcess = Runtime.getRuntime().exec("su -c 'getevent $touchDevice'")
            inputReader = BufferedReader(InputStreamReader(inputProcess!!.inputStream))

            isCollecting = true

            // 이벤트 읽기 루프
            while (isCollecting && inputReader != null) {
                try {
                    val line = inputReader!!.readLine()
                    if (line != null) {
                        parseInputEvent(line)
                    }
                } catch (e: IOException) {
                    break
                }
            }
        } catch (e: Exception) {
            if (context is MainActivity) {
                (context as MainActivity).addLog("❌ 입력 모니터링 실패: ${e.message}")
            }
        }
    }

    private suspend fun findTouchDevice(): String = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su -c 'cat /proc/bus/input/devices'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var currentDevice = ""
            var deviceName = ""

            reader.useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("I:") -> {
                            // 새로운 디바이스 시작
                            currentDevice = ""
                            deviceName = ""
                        }
                        line.startsWith("N:") -> {
                            // 디바이스 이름
                            deviceName = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'))
                        }
                        line.startsWith("H:") && line.contains("event") -> {
                            // 핸들러 정보에서 event 번호 추출
                            val eventMatch = Regex("event(\\d+)").find(line)
                            if (eventMatch != null) {
                                currentDevice = "/dev/input/event${eventMatch.groupValues[1]}"
                            }
                        }
                        line.startsWith("B:") && line.contains("EV=") -> {
                            // 이벤트 타입 확인 - 터치스크린인지 확인
                            if (line.contains("EV=b") || line.contains("EV=9")) { // ABS + KEY 이벤트
                                if (deviceName.contains("touch", ignoreCase = true) ||
                                    deviceName.contains("screen", ignoreCase = true) ||
                                    deviceName.contains("panel", ignoreCase = true)) {
                                    return@withContext currentDevice
                                }
                            }
                        }
                    }
                }
            }

            // 기본적으로 event0~5에서 터치스크린 찾기
            for (i in 0..5) {
                val device = "/dev/input/event$i"
                if (isTouchDevice(device)) {
                    return@withContext device
                }
            }

            ""
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun isTouchDevice(device: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su -c 'getevent -t $device'")
            // 짧은 시간 동안 이벤트를 확인하여 터치 디바이스인지 판단
            delay(100)
            process.destroy()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parseInputEvent(line: String) {
        try {
            // getevent 출력 파싱: [timestamp] type code value
            // 예: [  12345.678] 0003 0035 00000123
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 4) return

            val type = parts[1].toInt(16)
            val code = parts[2].toInt(16)
            val value = parts[3].toInt(16)

            when (type) {
                EV_ABS -> handleAbsoluteEvent(code, value)
                EV_KEY -> handleKeyEvent(code, value)
                EV_SYN -> handleSynEvent(code)
            }
        } catch (e: Exception) {
            // 파싱 에러 무시
        }
    }

    private fun handleAbsoluteEvent(code: Int, value: Int) {
        when (code) {
            ABS_X, ABS_MT_POSITION_X -> {
                currentX = value.toFloat()
            }
            ABS_Y, ABS_MT_POSITION_Y -> {
                currentY = value.toFloat()
            }
            ABS_PRESSURE, ABS_MT_PRESSURE -> {
                currentPressure = value / 255.0f // 0-1로 정규화
            }
            ABS_MT_TOUCH_MAJOR -> {
                currentSize = value.toFloat()
            }
        }
    }

    private fun handleKeyEvent(code: Int, value: Int) {
        when (code) {
            BTN_TOUCH -> {
                when (value) {
                    1 -> handleTouchDown() // 터치 시작
                    0 -> handleTouchUp()   // 터치 종료
                }
            }
        }
    }

    private fun handleSynEvent(code: Int) {
        if (code == SYN_REPORT) {
            // SYN_REPORT는 하나의 완전한 이벤트가 완료됨을 의미
            if (isTouchDown) {
                handleTouchMove()
            }
        }
    }

    private fun handleTouchDown() {
        val currentTime = System.currentTimeMillis()
        currentTouchSession = currentTime
        touchSessionStarted = true
        dragStartX = currentX
        dragStartY = currentY
        dragStartTime = currentTime
        isDragging = false
        totalMoveEvents = 0
        maxDistanceMoved = 0f
        allPositions.clear()
        allPositions.add(Pair(currentX, currentY))
        isTouchDown = true

        if (context is MainActivity) {
            (context as MainActivity).incrementEventCount("touch_session")
            (context as MainActivity).addLog("🔓 ROOT DOWN: (${String.format("%.1f", currentX)}, ${String.format("%.1f", currentY)})")
        }
    }

    private fun handleTouchMove() {
        if (!touchSessionStarted) return

        totalMoveEvents++
        allPositions.add(Pair(currentX, currentY))

        val deltaX = currentX - dragStartX
        val deltaY = currentY - dragStartY
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
        if (distance > maxDistanceMoved) maxDistanceMoved = distance

        if (!isDragging) {
            if ((totalMoveEvents >= 3 && maxDistanceMoved > DRAG_THRESHOLD) || distance >= DRAG_THRESHOLD) {
                isDragging = true
                if (context is MainActivity) {
                    (context as MainActivity).incrementEventCount("drag_event")
                    (context as MainActivity).addLog("🔓 ROOT 드래그 감지!")
                }
            }
        }
    }

    private fun handleTouchUp() {
        if (!touchSessionStarted) {
            isTouchDown = false
            return
        }

        val currentTime = System.currentTimeMillis()
        val touchDuration = currentTime - dragStartTime
        val deltaX = currentX - dragStartX
        val deltaY = currentY - dragStartY
        val totalDistance = sqrt(deltaX * deltaX + deltaY * deltaY)

        if (isDragging) {
            val params = mapOf(
                "timestamp" to currentTime,
                "action_type" to "drag",
                "start_x" to dragStartX,
                "start_y" to dragStartY,
                "end_x" to currentX,
                "end_y" to currentY,
                "total_distance" to totalDistance,
                "duration" to touchDuration,
                "drag_direction" to getDragDirection(deltaX, deltaY),
                "move_count" to totalMoveEvents,
                "touch_session" to currentTouchSession,
                "root_collection" to true, // ROOT로 수집됨을 표시
                "pressure" to currentPressure,
                "size" to currentSize
            )
            transmitTouchEventImmediately("drag", params)
        } else {
            val params = mapOf(
                "timestamp" to currentTime,
                "action_type" to "touch_pressure",
                "x" to currentX,
                "y" to currentY,
                "size" to currentSize,
                "pressure" to currentPressure,
                "touch_duration" to touchDuration,
                "touch_session" to currentTouchSession,
                "root_collection" to true
            )
            transmitTouchEventImmediately("touch_pressure", params)
            if (context is MainActivity) {
                (context as MainActivity).addLog("🔓 ROOT touch_pressure: duration=${touchDuration}ms")
            }
        }

        touchSessionStarted = false
        isDragging = false
        currentTouchSession = 0L
        totalMoveEvents = 0
        maxDistanceMoved = 0f
        allPositions.clear()
        isTouchDown = false
    }

    private fun getDragDirection(deltaX: Float, deltaY: Float): String {
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        return when {
            absX < 2 && absY < 2 -> "stationary"
            absX > absY + 3 -> if (deltaX > 0) "right" else "left"
            absY > absX + 3 -> if (deltaY > 0) "down" else "up"
            deltaX > 0 && deltaY > 0 -> "down_right"
            deltaX > 0 && deltaY < 0 -> "up_right"
            deltaX < 0 && deltaY > 0 -> "down_left"
            deltaX < 0 && deltaY < 0 -> "up_left"
            else -> "diagonal"
        }
    }

    private fun transmitTouchEventImmediately(eventType: String, params: Map<String, Any>) {
        coroutineScope.launch {
            try {
                behaviorDataManager.createAndAddLogForImmediateTransmission(eventType, params)
                if (context is MainActivity) {
                    (context as MainActivity).onDataTransmitted(1, "ROOT터치")
                    (context as MainActivity).addLog("🔓 ROOT $eventType 전송")
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ ROOT $eventType 전송 실패: ${e.message}")
                }
            }
        }
    }

    fun stopRootTouchCollection() {
        isCollecting = false

        try {
            inputReader?.close()
            inputProcess?.destroy()
        } catch (e: Exception) {
            // 무시
        }

        coroutineScope.cancel()
        touchSessionStarted = false
        isDragging = false
        isTouchDown = false

        if (context is MainActivity) {
            (context as MainActivity).addLog("🔓 ROOT 터치 수집 중지")
        }
    }
}