package com.example.policyengine.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

object DeviceUtils {

    /**
     * 디바이스 고유 지문 생성
     */
    fun getDeviceFingerprint(context: Context): String {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val model = "${Build.MANUFACTURER}-${Build.MODEL}"
        val combined = "$deviceId-$model"

        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(combined.toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            combined.hashCode().toString()
        }
    }

    /**
     * 앱 버전 가져오기
     */
    fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }

    /**
     * 네트워크 타입 가져오기
     */
    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

                when {
                    networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                    networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                    networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> "Unknown"
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = connectivityManager.activeNetworkInfo
                activeNetworkInfo?.typeName ?: "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}