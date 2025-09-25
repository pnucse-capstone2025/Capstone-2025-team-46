package com.example.policyengine.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.Toast
import com.example.policyengine.BehaviorDataManager
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayTouchService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var behaviorDataManager: BehaviorDataManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 터치 이벤트 처리 (기존 TouchEventCollector와 동일한 로직)
    private val DRAG_THRESHOLD = 6 * resources.displayMetrics.density // 6dp

    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartTime = 0L

    private var totalMoveEvents = 0
    private var maxDistanceMoved = 0f
    private var currentTouchSession = 0L
    private var touchSessionStarted = false

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234

        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // BehaviorDataManager 초기화
        try {
            behaviorDataManager = BehaviorDataManager.getInstance()
        } catch (e: Exception) {
            BehaviorDataManager.initialize(this)
            behaviorDataManager = BehaviorDataManager.getInstance()
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (hasOverlayPermission(this)) {
            createOverlayView()
        } else {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlayView() {
        try {
            // 투명한 전체 화면 오버레이 생성
            overlayView = object : View(this) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    handleOverlayTouch(event)
                    // false 반환으로 터치 이벤트를 하위 뷰로 전달 (다른 앱 사용 가능)
                    return false
                }
            }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(overlayView, layoutParams)

            // 성공 로그
            android.util.Log.d("OverlayTouchService", "오버레이 터치 수집 시작")

        } catch (e: Exception) {
            android.util.Log.e("OverlayTouchService", "오버레이 생성 실패: ${e.message}")
            stopSelf()
        }
    }

    private fun handleOverlayTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(event)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event)
            MotionEvent.ACTION_UP -> handleTouchUp(event)
            MotionEvent.ACTION_CANCEL -> handleTouchCancel()
            MotionEvent.ACTION_OUTSIDE -> {
                // 오버레이 밖 터치 (다른 앱에서의 터치)
                android.util.Log.d("OverlayTouchService", "외부 터치 감지: ${event.x}, ${event.y}")
            }
        }
    }

    private fun handleTouchDown(event: MotionEvent) {
        val currentTime = System.currentTimeMillis()
        currentTouchSession = currentTime
        touchSessionStarted = true
        dragStartX = event.x
        dragStartY = event.y
        dragStartTime = currentTime
        isDragging = false
        totalMoveEvents = 0
        maxDistanceMoved = 0f

        android.util.Log.d("OverlayTouchService", "오버레이 DOWN: (${event.x}, ${event.y})")
    }

    private fun handleTouchMove(event: MotionEvent) {
        if (!touchSessionStarted) return

        totalMoveEvents++
        val deltaX = event.x - dragStartX
        val deltaY = event.y - dragStartY
        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

        if (distance > maxDistanceMoved) maxDistanceMoved = distance

        if (!isDragging) {
            if ((totalMoveEvents >= 3 && maxDistanceMoved > DRAG_THRESHOLD) || distance >= DRAG_THRESHOLD) {
                isDragging = true
                android.util.Log.d("OverlayTouchService", "오버레이 드래그 감지!")
            }
        }
    }

    private fun handleTouchUp(event: MotionEvent) {
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
                "touch_session" to currentTouchSession,
                "overlay_collection" to true, // 오버레이로 수집됨을 표시
                "pressure" to event.pressure,
                "size" to event.size
            )
            transmitTouchEventImmediately("drag", params)
        } else {
            val params = mapOf(
                "timestamp" to currentTime,
                "action_type" to "touch_pressure",
                "x" to event.x,
                "y" to event.y,
                "size" to event.size,
                "pressure" to event.pressure,
                "touch_duration" to touchDuration,
                "touch_session" to currentTouchSession,
                "overlay_collection" to true
            )
            transmitTouchEventImmediately("touch_pressure", params)
        }

        touchSessionStarted = false
        isDragging = false
        currentTouchSession = 0L
        totalMoveEvents = 0
        maxDistanceMoved = 0f

        android.util.Log.d("OverlayTouchService", "오버레이 UP: duration=${touchDuration}ms")
    }

    private fun handleTouchCancel() {
        touchSessionStarted = false
        isDragging = false
        currentTouchSession = 0L
        totalMoveEvents = 0
        maxDistanceMoved = 0f
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
        serviceScope.launch {
            try {
                behaviorDataManager.createAndAddLogForImmediateTransmission(eventType, params)
                android.util.Log.d("OverlayTouchService", "오버레이 $eventType 전송 성공")
            } catch (e: Exception) {
                android.util.Log.e("OverlayTouchService", "오버레이 $eventType 전송 실패: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            overlayView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayTouchService", "오버레이 제거 실패: ${e.message}")
        }

        serviceScope.cancel()
        android.util.Log.d("OverlayTouchService", "오버레이 터치 수집 중지")
    }
}