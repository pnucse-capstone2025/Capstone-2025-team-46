package com.example.policyengine.collector

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.example.policyengine.BehaviorDataManager
import com.example.policyengine.MainActivity
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

// 버튼 클릭은 살리면서 전 화면 터치 관찰
class TouchEventCollector(private val context: Context) {

    private val behaviorDataManager = BehaviorDataManager.getInstance()
    private var coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val DRAG_THRESHOLD = context.resources.displayMetrics.density * 6 // 약 6dp

    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartTime = 0L

    private var totalMoveEvents = 0
    private var maxDistanceMoved = 0f
    private var allPositions = mutableListOf<Pair<Float, Float>>()

    private var currentTouchSession = 0L
    private var touchSessionStarted = false

    private var forceAllMovementAsDrag = false

    // 전역 이벤트 훅: 액티비티 dispatchTouchEvent에서 호출
    fun onDispatch(event: MotionEvent) {
        logAllTouchEvents(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(dummyView, event)
            MotionEvent.ACTION_MOVE -> handleTouchMove(dummyView, event)
            MotionEvent.ACTION_UP -> handleTouchUp(dummyView, event)
            MotionEvent.ACTION_CANCEL -> handleTouchCancel()
        }
    }

    // setupTouchListener는 더 이상 사용하지 않음(버튼 클릭을 막기 때문)
    @Deprecated("Use onDispatch from Activity instead")
    fun setupTouchListener(view: View) { /* no-op */ }

    private val dummyView = object : View(context) {}

    private fun logAllTouchEvents(event: MotionEvent) {
        val actionName = when (event.action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER"
        }
        if (context is MainActivity) {
            val distance = if (touchSessionStarted) {
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                sqrt(dx * dx + dy * dy)
            } else 0f
            (context as MainActivity).addLog(
                "RAW $actionName: (${String.format("%.1f", event.x)}, ${String.format("%.1f", event.y)}) " +
                        "거리=${String.format("%.1f", distance)}px 드래그=${if (isDragging) "Y" else "N"}"
            )
        }
    }

    private fun handleTouchDown(view: View, event: MotionEvent) {
        val currentTime = System.currentTimeMillis()
        currentTouchSession = currentTime
        touchSessionStarted = true
        dragStartX = event.x
        dragStartY = event.y
        dragStartTime = currentTime
        isDragging = false
        totalMoveEvents = 0
        maxDistanceMoved = 0f
        allPositions.clear()
        allPositions.add(Pair(event.x, event.y))

        if (context is MainActivity) {
            (context as MainActivity).incrementEventCount("touch_session")
            (context as MainActivity).addLog("DOWN 처리: 시작점(${String.format("%.1f", dragStartX)}, ${String.format("%.1f", dragStartY)})")
        }
    }

    private fun handleTouchMove(view: View, event: MotionEvent) {
        if (!touchSessionStarted) return
        totalMoveEvents++
        allPositions.add(Pair(event.x, event.y))

        val deltaX = event.x - dragStartX
        val deltaY = event.y - dragStartY
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
        if (distance > maxDistanceMoved) maxDistanceMoved = distance

        if (!isDragging) {
            val shouldBeDrag = when {
                forceAllMovementAsDrag && totalMoveEvents > 0 -> "강제모드"
                (totalMoveEvents >= 3 && maxDistanceMoved > DRAG_THRESHOLD) -> "충분한 MOVE"
                distance >= DRAG_THRESHOLD -> "거리충분"
                else -> null
            }
            if (shouldBeDrag != null) {
                isDragging = true
                if (context is MainActivity) {
                    (context as MainActivity).incrementEventCount("drag_event")
                    (context as MainActivity).addLog("드래그 감지! 사유: $shouldBeDrag")
                }
            }
        }
    }

    private fun handleTouchUp(view: View, event: MotionEvent) {
        if (!touchSessionStarted) return
        val currentTime = System.currentTimeMillis()
        val touchDuration = currentTime - dragStartTime
        val deltaX = event.x - dragStartX
        val deltaY = event.y - dragStartY
        val totalDistance = sqrt(deltaX * deltaX + deltaY * deltaY)

        if (isDragging) {
            val params = mapOf(
                "timestamp" to currentTime,
                "action_type" to "drag",
                "start_x" to dragStartX,
                "start_y" to dragStartY,
                "end_x" to event.x,
                "end_y" to event.y,
                "total_distance" to totalDistance,
                "duration" to touchDuration,
                "drag_direction" to getDragDirection(deltaX, deltaY),
                "move_count" to totalMoveEvents,
                "touch_session" to currentTouchSession
            )
            transmitTouchEventImmediately("drag", params)
        } else {
            val params = mapOf(
                "timestamp" to currentTime,
                "action_type" to "touch_pressure",
                "x" to event.x,
                "y" to event.y,
                "size" to event.size,
                "touch_duration" to touchDuration,
                "touch_session" to currentTouchSession
            )
            transmitTouchEventImmediately("touch_pressure", params)
            if (context is MainActivity) {
                (context as MainActivity).addLog("👆 touch_pressure 종료: duration=${touchDuration}ms")
            }
        }

        touchSessionStarted = false
        isDragging = false
        currentTouchSession = 0L
        totalMoveEvents = 0
        maxDistanceMoved = 0f
        allPositions.clear()
    }

    private fun handleTouchCancel() {
        if (context is MainActivity) {
            (context as MainActivity).addLog("터치 취소 - MOVE=$totalMoveEvents")
        }
        touchSessionStarted = false
        isDragging = false
        currentTouchSession = 0L
        totalMoveEvents = 0
        maxDistanceMoved = 0f
        allPositions.clear()
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
        // stop 후 재시작을 위해 scope가 취소되었을 수 있어 재생성 가드
        if (!coroutineScope.isActive) {
            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        coroutineScope.launch {
            try {
                behaviorDataManager.createAndAddLogForImmediateTransmission(eventType, params)
                if (context is MainActivity) {
                    (context as MainActivity).onDataTransmitted(1, "터치")
                    (context as MainActivity).addLog("📤 $eventType 전송 트리거")
                }
            } catch (e: Exception) {
                if (context is MainActivity) {
                    (context as MainActivity).addLog("❌ $eventType 전송 실패: ${e.message}")
                }
            }
        }
    }

    fun stopTouchCollection() {
        coroutineScope.cancel()
        touchSessionStarted = false
        isDragging = false
        if (context is MainActivity) {
            (context as MainActivity).addLog("🛑 드래그 감지 중지")
        }
    }
}
