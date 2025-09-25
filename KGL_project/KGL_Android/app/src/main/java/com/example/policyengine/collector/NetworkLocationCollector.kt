package com.example.policyengine.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.core.app.ActivityCompat
import com.example.policyengine.BehaviorDataManager
import com.example.policyengine.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

class NetworkLocationCollector(private val context: Context) : LocationListener {

    private val behaviorDataManager = BehaviorDataManager.getInstance()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isLocationListenerRegistered = false
    private val COLLECTION_INTERVAL = 1000L
    private val sequenceCounter = AtomicLong(0L)

    // 현재 위치와 네트워크 상태
    private var currentLatitude = 0.0
    private var currentLongitude = 0.0
    private var currentNetworkType = "none"

    fun startNetworkLocationCollection() {
        startNetworkMonitoring()
        startLocationMonitoring()

        if (context is MainActivity) {
            (context as MainActivity).addLog("🌐📍 위치/네트워크 수집 시작")
        }
    }

    private fun startNetworkMonitoring() {
        coroutineScope.launch {
            while (true) {
                try {
                    updateNetworkType()
                    sendCurrentData()
                } catch (e: Exception) {
                    // 무시
                }
                delay(COLLECTION_INTERVAL)
            }
        }
    }

    private fun startLocationMonitoring() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, COLLECTION_INTERVAL, 0f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, COLLECTION_INTERVAL, 0f, this)
            }
            isLocationListenerRegistered = true
        } catch (e: SecurityException) {
            // 무시
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

    private fun sendCurrentData() {
        val data = mapOf(
            "seq" to sequenceCounter.incrementAndGet(),
            "lat" to currentLatitude,
            "lng" to currentLongitude,
            "net_type" to currentNetworkType
        )

        behaviorDataManager.createAndAddLog("network_status", data)
    }

    override fun onLocationChanged(location: Location) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude

        if (context is MainActivity) {
            (context as MainActivity).addLog("📍 위치: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}")
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    fun stopNetworkLocationCollection() {
        coroutineScope.cancel()
        if (isLocationListenerRegistered) {
            try {
                locationManager.removeUpdates(this)
                isLocationListenerRegistered = false
            } catch (e: SecurityException) {
                // 무시
            }
        }
    }
}