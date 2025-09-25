package com.example.policyengine

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.policyengine.collector.NetworkLocationCollector
import com.example.policyengine.collector.RootTouchCollector
import com.example.policyengine.collector.SensorDataCollector
import com.example.policyengine.collector.TouchEventCollector
import com.example.policyengine.network.ApiService
import com.example.policyengine.service.BackgroundBehaviorCollectionService
import com.example.policyengine.service.OverlayTouchService
import com.example.policyengine.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // IP 변경 감지 관련 변수 (개선)
    private var previousLocalIP: String? = null
    private var previousPublicIP: String? = null
    private var ipCheckJob: Job? = null
    private var ipChangedHandled = false

    private var isRecoveryInProgress = false
    private var lastAnomalyLockTime = 0L

    // 이상반응 관련 변수
    private lateinit var anomalyManager: ApiService.AnomalyManager
    private var pollingJob: Job? = null

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // 백그라운드 서비스 관련 변수
    private var isBackgroundServiceRunning = false
    private var rootTouchCollector: RootTouchCollector? = null
    private var isRootAvailable = false
    private var isOverlayAvailable = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        private const val MAX_LOG_LINES = 1000
        private const val TRIM_TO_LINES = 900
    }

    // UI
    private lateinit var currentTime: TextView
    private lateinit var collectionStatus: TextView
    private lateinit var totalEventsCount: TextView
    private lateinit var queueSizeCount: TextView
    private lateinit var sequenceIndexCount: TextView
    private lateinit var lastTransmission: TextView
    private lateinit var logText: TextView
    private lateinit var logIndicator: TextView
    private lateinit var manualSendCard: CardView
    private lateinit var settingsCard: CardView
    private lateinit var startStopLabel: TextView
    private lateinit var logScroll: ScrollView

    // 컴포넌트
    private lateinit var behaviorDataManager: BehaviorDataManager
    private var touchCollector: TouchEventCollector? = null
    private var sensorCollector: SensorDataCollector? = null
    private var networkLocationCollector: NetworkLocationCollector? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var isCollectionActive = false

    // 색상 태그
    private val colorTouch by lazy { ContextCompat.getColor(this, android.R.color.holo_blue_light) }
    private val colorSensor by lazy { ContextCompat.getColor(this, android.R.color.holo_green_light) }
    private val colorNetwork by lazy { ContextCompat.getColor(this, android.R.color.holo_purple) }
    private val colorDefault by lazy { ContextCompat.getColor(this, android.R.color.darker_gray) }

    private data class LogLine(val text: String, val color: Int)
    private val liveLog = ArrayDeque<LogLine>(MAX_LOG_LINES + 64)

    // 자동 스크롤
    @Volatile private var followLive = true
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var flushPending = false

    // 통계
    @Volatile private var totalEvents = 0
    @Volatile private var touchEvents = 0
    @Volatile private var sensorEvents = 0
    @Volatile private var networkEvents = 0
    @Volatile private var currentQueueSize = 0
    @Volatile private var currentSequenceIndex = 0L
    @Volatile private var lastTransmissionTime = "--:--:--"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        checkAndRequestPermissions()
        initializeApp()
        setupEventListeners()
        startTimeUpdater()
        startStatisticsUpdater()
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, PolicyAdminReceiver::class.java)
    }

    private fun initializeViews() {
        try {
            currentTime = findViewById(R.id.current_time)
            collectionStatus = findViewById(R.id.collection_status)
            totalEventsCount = findViewById(R.id.total_events_count)
            queueSizeCount = findViewById(R.id.queue_size_count)
            sequenceIndexCount = findViewById(R.id.sequence_index_count)
            lastTransmission = findViewById(R.id.last_transmission)
            logText = findViewById(R.id.log_text)
            logIndicator = findViewById(R.id.log_indicator)
            manualSendCard = findViewById(R.id.manual_send_card)
            settingsCard = findViewById(R.id.settings_card)
            startStopLabel = findViewById(R.id.start_stop_label)
            logScroll = findViewById(R.id.log_scroll)

            logText.movementMethod = ScrollingMovementMethod()

            updateCurrentTime()
            updateCollectionStatus(false)
            updateStartStopButtonUI(false)
            updateStatisticsUI()

            addLog("📱 Policy Engine 시작 (수동 시작 모드)")
        } catch (e: Exception) {
            addLog("❌ UI 초기화 실패: ${e.message}")
        }

        logScroll.viewTreeObserver.addOnScrollChangedListener {
            val atBottom = isAtBottom()
            if (!atBottom && followLive) {
                followLive = false
                logIndicator.text = "⏸ PAUSED"
                addLog("[시스템] 자동 스크롤 일시정지 (사용자 스크롤)")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            addLog("✅ 권한 이미 승인됨")
            updateCollectionStatus(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) addLog("✅ 권한 승인 완료") else addLog("⚠️ 일부 권한 거부됨")
            updateCollectionStatus(false)
        }
    }

    private fun initializeApp() {
        try {
            BehaviorDataManager.initialize(this)
            behaviorDataManager = BehaviorDataManager.getInstance()

            anomalyManager = ApiService.AnomalyManager(this)

            addLog("✅ 초기화 완료")

            checkRootPermission()
            checkOverlayPermission()

            // 초기 IP 주소 설정
            initializeIPAddresses()

        } catch (e: Exception) {
            addLog("❌ 초기화 실패: ${e.message}")
        }
    }

    // 초기 IP 주소 설정
    private fun initializeIPAddresses() {
        lifecycleScope.launch {
            try {
                previousLocalIP = getCurrentLocalIP()
                previousPublicIP = getCurrentPublicIP()
                addLog("초기 IP 설정 - 로컬: $previousLocalIP, 공인: $previousPublicIP")
            } catch (e: Exception) {
                addLog("초기 IP 설정 실패: ${e.message}")
            }
        }
    }

    // 현재 로컬 IP 가져오기
    private fun getCurrentLocalIP(): String? {
        return try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress

            if (ip != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    (ip and 0xff),
                    (ip shr 8 and 0xff),
                    (ip shr 16 and 0xff),
                    (ip shr 24 and 0xff)
                )
            } else {
                NetworkUtils.getCurrentIpAddress()
            }
        } catch (e: Exception) {
            null
        }
    }

    // 현재 공인 IP 가져오기
    private suspend fun getCurrentPublicIP(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://api.ipify.org")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.trim()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun checkOverlayPermission() {
        isOverlayAvailable = OverlayTouchService.hasOverlayPermission(this)
        if (isOverlayAvailable) {
            addLog("🔲 오버레이 권한 확인됨 - 전체 화면 터치 수집 가능")
        } else {
            addLog("⚠️ 오버레이 권한 없음 - 앱 내 터치만 수집")
        }
    }

    private fun requestOverlayPermission() {
        if (!isOverlayAvailable) {
            OverlayTouchService.requestOverlayPermission(this)
            addLog("🔲 오버레이 권한 설정 화면으로 이동")
        } else {
            addLog("✅ 오버레이 권한 이미 허용됨")
        }
    }

    private fun requestDeviceAdminPermission() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "보안을 위해 화면 잠금 권한이 필요합니다")
        startActivityForResult(intent, 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK) {
                addLog("Device Admin 권한 획득 성공")
            } else {
                addLog("Device Admin 권한 거부됨")
            }
        }
    }

    private fun setupEventListeners() {
        manualSendCard.setOnClickListener {
            if (isCollectionActive) stopCollection() else startCollection()
        }

        settingsCard.setOnClickListener {
            incrementEventCount("ui_action")
            showSettings()
        }

        logIndicator.setOnClickListener {
            followLive = !followLive
            logIndicator.text = if (followLive) "🟢 LIVE" else "⏸ PAUSED"
            if (followLive) {
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun isAtBottom(): Boolean {
        val diff = logText.bottom - (logScroll.height + logScroll.scrollY)
        return diff <= 32
    }

    private fun startCollection() {
        try {
            behaviorDataManager.setTransmissionEnabled(true)

            touchCollector = TouchEventCollector(this)
            rootTouchCollector = RootTouchCollector(this)
            rootTouchCollector?.startRootTouchCollection()
            sensorCollector = SensorDataCollector(this).also { it.startSensorCollection() }
            networkLocationCollector = NetworkLocationCollector(this).also { it.startNetworkLocationCollection() }

            startAnomalyPolling()

            isCollectionActive = true
            updateCollectionStatus(true)
            updateStartStopButtonUI(true)
            addLog("🚀 수집/전송 시작 (ROOT 터치 포함)")

            // IP 변경 모니터링 시작
            startImprovedIPMonitoring()

        } catch (e: Exception) {
            addLog("❌ 수집 시작 실패: ${e.message}")
            updateCollectionStatus(false)
            updateStartStopButtonUI(false)
        }
    }

    // 개선된 IP 변경 모니터링
    private fun startImprovedIPMonitoring() {
        ipCheckJob = lifecycleScope.launch {
            ipChangedHandled = false

            while (isCollectionActive) {
                try {
                    val currentLocalIP = getCurrentLocalIP()
                    val currentPublicIP = getCurrentPublicIP()

                    var hasIPChanged = false
                    val changes = mutableListOf<String>()

                    // 로컬 IP 변경 감지
                    if (previousLocalIP != null && currentLocalIP != null &&
                        currentLocalIP != previousLocalIP) {
                        changes.add("로컬 IP: $previousLocalIP → $currentLocalIP")
                        previousLocalIP = currentLocalIP
                        hasIPChanged = true
                    }

                    // 공인 IP 변경 감지
                    if (previousPublicIP != null && currentPublicIP != null &&
                        currentPublicIP != previousPublicIP) {
                        changes.add("공인 IP: $previousPublicIP → $currentPublicIP")
                        previousPublicIP = currentPublicIP
                        hasIPChanged = true
                    }

                    // IP 변경이 감지되고 아직 처리되지 않은 경우
                    if (hasIPChanged && !ipChangedHandled) {
                        val changeMessage = changes.joinToString("\n")
                        addLog("🚨 IP 주소 변경 감지!\n$changeMessage")

                        val isVpnActive = checkVPNStatus()
                        showEnhancedNetworkAnomalyDialog(changeMessage, isVpnActive)
                        ipChangedHandled = true
                    }

                } catch (e: Exception) {
                    addLog("IP 모니터링 오류: ${e.message}")
                }

                delay(5000)
            }
        }
    }

    // VPN 상태 확인
    private fun checkVPNStatus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } else {
            false
        }
    }

    // MainActivity.kt - 수정된 showEnhancedNetworkAnomalyDialog 함수

    private fun showEnhancedNetworkAnomalyDialog(changeMessage: String, isVpnActive: Boolean) {
        runOnUiThread {
            val title = if (isVpnActive) "VPN으로 인한 IP 변경 감지" else "네트워크 IP 변경 감지"
            val vpnWarning = if (isVpnActive) "\n\n⚠️ VPN 사용이 감지되었습니다." else ""

            AlertDialog.Builder(this)
                .setTitle("🌐 $title")
                .setMessage("$changeMessage$vpnWarning\n\n이는 보안상 중요한 변화일 수 있습니다.\n어떤 조치를 취하시겠습니까?")
                .setCancelable(false)
                .setPositiveButton("비행기 모드 설정") { _, _ ->
                    openAirplaneModeSettings()
                }
                .setNeutralButton("WiFi 설정") { _, _ ->
                    openWiFiSettings()
                }
                .setNegativeButton("무시하고 계속") { _, _ ->
                    addLog("사용자가 IP 변경 경고를 무시함")
                    lifecycleScope.launch {
                        delay(300000) // 5분 후 플래그 리셋
                        ipChangedHandled = false
                    }
                }
                .show()
        }
    }

    // 네트워크 설정 화면들
    private fun openAirplaneModeSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            } else {
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            addLog("✈️ 비행기 모드 설정 화면으로 이동")
        } catch (e: Exception) {
            addLog("❌ 비행기 모드 설정 화면 이동 실패: ${e.message}")
            openWirelessSettings()
        }
    }

    private fun openWiFiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            addLog("📶 WiFi 설정 화면으로 이동")
        } catch (e: Exception) {
            addLog("❌ WiFi 설정 화면 이동 실패: ${e.message}")
            openWirelessSettings()
        }
    }

    private fun openMobileDataSettings() {
        try {
            val intent = Intent().apply {
                action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Settings.ACTION_DATA_USAGE_SETTINGS
                } else {
                    Settings.ACTION_DATA_ROAMING_SETTINGS
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            addLog("📱 모바일 데이터 설정 화면으로 이동")
        } catch (e: Exception) {
            addLog("❌ 모바일 데이터 설정 화면 이동 실패: ${e.message}")
            openWirelessSettings()
        }
    }

    private fun openWirelessSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            addLog("⚙️ 무선 네트워크 설정 화면으로 이동")
        } catch (e: Exception) {
            addLog("❌ 네트워크 설정 화면 이동 실패: ${e.message}")
        }
    }

    private fun checkRootPermission() {
        lifecycleScope.launch {
            try {
                val process = withContext(Dispatchers.IO) {
                    Runtime.getRuntime().exec("su -c 'echo test'")
                }
                val result = withContext(Dispatchers.IO) {
                    process.waitFor()
                }

                isRootAvailable = (result == 0)
                if (isRootAvailable) {
                    addLog("🔓 ROOT 권한 확인됨 - 시스템 레벨 터치 수집 가능")
                } else {
                    addLog("⚠️ ROOT 권한 없음 - 앱 내 터치만 수집")
                }
            } catch (e: Exception) {
                isRootAvailable = false
                addLog("⚠️ ROOT 권한 확인 실패 - 앱 내 터치만 수집")
            }
        }
    }

    private fun stopCollection() {
        try {
            behaviorDataManager.setTransmissionEnabled(false)
            sensorCollector?.stopSensorCollection()
            networkLocationCollector?.stopNetworkLocationCollection()
            touchCollector?.stopTouchCollection()
            rootTouchCollector?.stopRootTouchCollection()

            stopAnomalyPolling()

            sensorCollector = null
            networkLocationCollector = null
            touchCollector = null
            rootTouchCollector = null

            isCollectionActive = false
            updateCollectionStatus(false)
            updateStartStopButtonUI(false)
            addLog("🛑 수집/전송 중지 (큐 대기)")

            // IP 모니터링 중지
            ipCheckJob?.cancel()
            ipCheckJob = null
            ipChangedHandled = false

        } catch (e: Exception) {
            addLog("❌ 수집 중지 실패: ${e.message}")
        }
    }

    private fun startAnomalyPolling() {
        pollingJob = lifecycleScope.launch {
            addLog("이상반응 모니터링 시작")
            while (true) {
                try {
                    anomalyManager.checkForAnomalies()
                    delay(5000)
                } catch (e: Exception) {
                    addLog("이상반응 체크 실패: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private fun stopAnomalyPolling() {
        pollingJob?.cancel()
        pollingJob = null
        addLog("이상반응 모니터링 중지")
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundBehaviorCollectionService::class.java)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            isBackgroundServiceRunning = true
            addLog("🔄 백그라운드 서비스 자동 시작")

        } catch (e: Exception) {
            addLog("❌ 백그라운드 서비스 시작 실패: ${e.message}")
        }
    }

    private fun stopBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundBehaviorCollectionService::class.java)
            stopService(serviceIntent)

            isBackgroundServiceRunning = false
            addLog("⏹ 백그라운드 서비스 중지")

        } catch (e: Exception) {
            addLog("❌ 백그라운드 서비스 중지 실패: ${e.message}")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try {
            if (isCollectionActive && !isRootAvailable) {
                touchCollector?.onDispatch(ev)
            }
        } catch (_: Exception) { }
        return super.dispatchTouchEvent(ev)
    }

    private fun updateStartStopButtonUI(isRunning: Boolean) {
        try {
            startStopLabel.text = if (isRunning) "중지" else "시작"
            val color = if (isRunning) 0xFFE11D48.toInt() else 0xFF10B981.toInt()
            manualSendCard.setCardBackgroundColor(color)
        } catch (_: Exception) { }
    }

    private fun showSettings() {
        val rootStatus = if (isRootAvailable) "사용 가능" else "사용 불가"
        val overlayStatus = if (isOverlayAvailable) "사용 가능" else "사용 불가"

        val menu = arrayOf(
            "📊 이상반응 현황",
            "📍 현재 IP 정보",
            "🗑️ 데이터 초기화",
            "🔄 이상반응 카운터 초기화",
            "🛡️ Device Admin 권한 요청",
            "✅ Device Admin 상태 확인",
            "ℹ️ 앱 정보"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ 설정")
            .setItems(menu) { _, which ->
                when (which) {
                    0 -> showAnomalyStatus()
                    1 -> showCurrentIPInfo()
                    2 -> showDataResetDialog()
                    3 -> showAnomalyResetDialog()
                    4 -> requestDeviceAdminPermission()
                    5 -> checkDeviceAdminStatus()
                    6 -> showAppInfo()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // 현재 IP 정보 표시
    private fun showCurrentIPInfo() {
        val vpnStatus = if (checkVPNStatus()) "사용 중" else "사용 안함"
        val ipMonitoringStatus = if (ipCheckJob?.isActive == true) "활성" else "비활성"

        val ipInfo = """
            현재 로컬 IP: ${previousLocalIP ?: "확인 중..."}
            현재 공인 IP: ${previousPublicIP ?: "확인 중..."}
            VPN 상태: $vpnStatus
            
            IP 모니터링: $ipMonitoringStatus
            변경 감지 처리됨: $ipChangedHandled
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("📍 현재 IP 정보")
            .setMessage(ipInfo)
            .setPositiveButton("새로고침") { _, _ ->
                lifecycleScope.launch {
                    addLog("IP 정보 수동 새로고침")
                    initializeIPAddresses()
                }
            }
            .setNeutralButton("모니터링 재시작") { _, _ ->
                if (isCollectionActive) {
                    ipChangedHandled = false
                    addLog("IP 변경 감지 플래그 리셋")
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun checkDeviceAdminStatus() {
        val isActive = devicePolicyManager.isAdminActive(adminComponent)
        val message = if (isActive) "Device Admin 권한 활성화됨" else "Device Admin 권한 비활성화됨"

        addLog(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showAnomalyStatus() {
        val counts = anomalyManager.getAllCounts()
        val totalAnomalies = counts.values.sum()

        val statusMessage = """
            총 이상반응: ${totalAnomalies}개
            
            네트워크: ${counts["network"]}개
            센서: ${counts["sensor"]}개  
            터치압력: ${counts["touch_pressure"]}개
            터치드래그: ${counts["touch_drag"]}개
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("이상반응 현황")
            .setMessage(statusMessage)
            .setPositiveButton("새로고침") { _, _ ->
                lifecycleScope.launch {
                    anomalyManager.checkForAnomalies()
                    showAnomalyStatus()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showAnomalyResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("이상반응 카운터 초기화")
            .setMessage("모든 이상반응 카운터를 0으로 초기화하시겠습니까?")
            .setPositiveButton("초기화") { _, _ ->
                anomalyManager.resetCounts()
                addLog("이상반응 카운터 초기화 완료")
                Toast.makeText(this, "이상반응 카운터 초기화 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDataResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔄 데이터 초기화")
            .setMessage("통계와 수집된 데이터를 모두 초기화하시겠습니까?")
            .setPositiveButton("초기화") { _, _ ->
                try {
                    totalEvents = 0; touchEvents = 0; sensorEvents = 0; networkEvents = 0
                    currentQueueSize = 0; currentSequenceIndex = 0; lastTransmissionTime = "--:--:--"
                    behaviorDataManager.clearAllData()
                    synchronized(liveLog) { liveLog.clear() }
                    flushLogToTextView()
                    updateStatisticsUI()
                    addLog("🗑️ 모든 데이터 초기화 완료")
                    Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    addLog("❌ 초기화 실패: ${e.message}")
                }
            }
            .setNegativeButton("취소", null)
            .create()
            .show()
    }

    private fun showAppInfo() {
        val rootStatus = if (isRootAvailable) "ON" else "OFF"

        val appInfo = """
            📱 Policy Engine v1.0 (ROOT 지원)
            총 이벤트: $totalEvents | 터치:$touchEvents 센서:$sensorEvents 네트워크:$networkEvents
            대기 중: $currentQueueSize | 시퀀스: $currentSequenceIndex
            전송: ${if (behaviorDataManager.isTransmissionEnabled()) "ON" else "OFF"}
            백그라운드: ${if (isBackgroundServiceRunning) "실행 중" else "중지됨"}
            ROOT 권한: $rootStatus
            현재 로컬 IP: ${previousLocalIP ?: "미확인"}
            현재 공인 IP: ${previousPublicIP ?: "미확인"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("ℹ️ 앱 정보")
            .setMessage(appInfo)
            .setPositiveButton("확인", null)
            .create()
            .show()
    }

    fun incrementEventCount(eventType: String) {
        totalEvents++
        when {
            eventType.contains("touch") || eventType.contains("drag") -> touchEvents++
            eventType.contains("sensor") -> sensorEvents++
            eventType.contains("network") || eventType.contains("location") -> networkEvents++
        }
        try {
            if (::behaviorDataManager.isInitialized) currentSequenceIndex = totalEvents.toLong()
        } catch (_: Exception) { }
    }

    fun updateQueueSize(size: Int) { currentQueueSize = size }

    fun onDataTransmitted(count: Int, category: String) {
        lastTransmissionTime = timeFormat.format(Date())
    }

    private fun startStatisticsUpdater() {
        lifecycleScope.launch {
            while (true) {
                try {
                    updateStatisticsUI()
                    delay(500)
                } catch (_: Exception) {
                    delay(2000)
                }
            }
        }
    }

    private fun updateStatisticsUI() {
        try {
            totalEventsCount.text = totalEvents.toString()
            queueSizeCount.text = currentQueueSize.toString()
            sequenceIndexCount.text = currentSequenceIndex.toString()
            lastTransmission.text = lastTransmissionTime
        } catch (e: Exception) {
            totalEventsCount.text = "0"
            queueSizeCount.text = "0"
            sequenceIndexCount.text = "0"
            lastTransmission.text = "--:--:--"
        }
    }

    private fun updateCollectionStatus(isActive: Boolean) {
        try {
            val statusText = if (isActive) "🟢 수집 중" else "🔴 중지됨"
            val statusColor = if (isActive)
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else
                ContextCompat.getColor(this, android.R.color.holo_red_dark)

            collectionStatus.text = statusText
            collectionStatus.setTextColor(statusColor)

            if (isActive) {
                logIndicator.text = if (followLive) "🟢 LIVE" else "⏸ PAUSED"
            } else {
                logIndicator.text = "🔴 STOP"
            }
        } catch (_: Exception) { }
    }

    private fun startTimeUpdater() {
        lifecycleScope.launch {
            while (true) {
                try {
                    updateCurrentTime()
                    delay(1000)
                } catch (_: Exception) {
                    delay(5000)
                }
            }
        }
    }

    private fun updateCurrentTime() {
        try {
            currentTime.text = timeFormat.format(Date())
        } catch (_: Exception) {
            currentTime.text = "--:--:--"
        }
    }

    private fun colorFor(message: String): Int {
        val m = message.lowercase()

        if (listOf("network", "네트워크", "wifi", "cellular", "location", "위치", "gps", "🌐", "📍")
                .any { m.contains(it) }) return colorNetwork

        if (listOf("sensor", "센서", "accelerometer", "gyroscope", "자이로", "가속도", "🔬", "🌀", "📡")
                .any { m.contains(it) }) return colorSensor

        if (listOf("touch", "터치", "drag", "드래그", "👆", "📤", "raw", "move", "down", "up")
                .any { m.contains(it) }) return colorTouch

        return colorDefault
    }

    fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val lineText = "[$timestamp] $message"
        val color = colorFor(message)

        synchronized(liveLog) {
            liveLog.addLast(LogLine(lineText, color))
            if (liveLog.size > MAX_LOG_LINES) {
                repeat(liveLog.size - TRIM_TO_LINES) { liveLog.pollFirst() }
            }
        }

        if (!flushPending) {
            flushPending = true
            uiHandler.postDelayed({ flushLogToTextView() }, 100)
        }
    }

    private fun flushLogToTextView() {
        val atBottomBefore = isAtBottom()
        val sb = SpannableStringBuilder()

        synchronized(liveLog) {
            liveLog.forEach { line ->
                val start = sb.length
                sb.append(line.text).append('\n')
                val end = sb.length
                sb.setSpan(
                    ForegroundColorSpan(line.color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        try {
            logText.text = sb
        } catch (_: Exception) { }

        flushPending = false

        if (followLive || atBottomBefore) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onResume() {
        super.onResume()

        if (isRecoveryInProgress) {
            addLog("복구 진행 중 - 중복 실행 방지")
            return
        }

        checkAnomalyLockRecovery()

        incrementEventCount("app_resume")
        addLog("앱 활성화")

        if (isBackgroundServiceRunning) {
            stopBackgroundService()
        }
    }

    private fun checkAnomalyLockRecovery() {
        val prefs = getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
        val anomalyLockTriggered = prefs.getBoolean("anomaly_lock_triggered", false)
        val isAnomalyInitiated = prefs.getBoolean("is_anomaly_initiated_lock", false)
        val lockTimestamp = prefs.getLong("lock_timestamp", 0)

        val currentTime = System.currentTimeMillis()
        val timeSinceLock = currentTime - lockTimestamp
        val isRecentLock = timeSinceLock < 60000
        val isValidAnomalyLock = anomalyLockTriggered && isAnomalyInitiated && lockTimestamp > 0

        val timeSinceLastAnomaly = currentTime - lastAnomalyLockTime
        val isNotDuplicateCheck = timeSinceLastAnomaly > 30000

        if (isValidAnomalyLock && isRecentLock && isNotDuplicateCheck && !isRecoveryInProgress) {
            lastAnomalyLockTime = currentTime
            isRecoveryInProgress = true

            val anomalyType = prefs.getString("anomaly_type", "unknown")
            addLog("이상반응 보안 잠금 해제 감지: $anomalyType (${timeSinceLock}ms 전 잠금)")

            prefs.edit().apply {
                remove("anomaly_lock_triggered")
                remove("anomaly_type")
                remove("lock_timestamp")
                remove("is_anomaly_initiated_lock")
                apply()
            }

            performAnomalyRecovery(anomalyType ?: "unknown")
        } else if (anomalyLockTriggered || isAnomalyInitiated) {
            if (!isRecentLock) {
                addLog("오래된 이상반응 플래그 정리 (${timeSinceLock}ms 전)")
                prefs.edit().apply {
                    remove("anomaly_lock_triggered")
                    remove("anomaly_type")
                    remove("lock_timestamp")
                    remove("is_anomaly_initiated_lock")
                    apply()
                }
            }
        }
    }

    private fun performAnomalyRecovery(anomalyType: String) {
        anomalyManager.temporaryDisable()

        addLog("보안 위반 복구 모드 시작: $anomalyType 이상반응으로 인한 시스템 재설정")

        anomalyManager.resetCounts()

        if (isCollectionActive) {
            stopCollection()
            addLog("수집 중지 - 이상반응 복구로 인함")
        }

        updateCollectionStatus(false)
        updateStartStopButtonUI(false)

        showAnomalyRecoveryDialog(anomalyType)
        anomalyManager.reEnable()
    }

    private fun performFullReset() {
        totalEvents = 0
        touchEvents = 0
        sensorEvents = 0
        networkEvents = 0
        currentQueueSize = 0
        currentSequenceIndex = 0
        lastTransmissionTime = "--:--:--"

        behaviorDataManager.clearAllData()
        synchronized(liveLog) { liveLog.clear() }
        flushLogToTextView()
        updateStatisticsUI()

        addLog("전체 데이터 초기화 완료")
    }

    private fun showAnomalyRecoveryDialog(anomalyType: String) {
        AlertDialog.Builder(this)
            .setTitle("보안 시스템 복구")
            .setMessage("${anomalyType} 이상반응이 감지되어 보안 잠금이 실행되었습니다.\n\n이상반응 카운터가 초기화되었습니다.\n수집을 다시 시작하려면 시작 버튼을 눌러주세요.")
            .setCancelable(false)
            .setPositiveButton("확인") { _, _ ->
                isRecoveryInProgress = false
                addLog("보안 복구 완료 - 수동 재시작 대기")
            }
            .setNeutralButton("전체 초기화") { _, _ ->
                performFullReset()
                isRecoveryInProgress = false
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        addLog("앱 비활성화")

        if (!isRecoveryInProgress) {
            val prefs = getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            val lockTimestamp = prefs.getLong("lock_timestamp", 0)
            val timeSinceLock = System.currentTimeMillis() - lockTimestamp

            if (timeSinceLock > 300000) {
                prefs.edit().apply {
                    remove("anomaly_lock_triggered")
                    remove("anomaly_type")
                    remove("lock_timestamp")
                    remove("is_anomaly_initiated_lock")
                    apply()
                }
                addLog("오래된 이상반응 플래그 정리")
            }
        }

        if (!isBackgroundServiceRunning) {
            startBackgroundService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            sensorCollector?.stopSensorCollection()
            networkLocationCollector?.stopNetworkLocationCollection()
            touchCollector?.stopTouchCollection()
            rootTouchCollector?.stopRootTouchCollection()

            stopAnomalyPolling()

            // IP 모니터링 중지
            ipCheckJob?.cancel()

            addLog("📱 앱 종료 - 백그라운드 수집 계속")

        } catch (_: Exception) { }
    }
}