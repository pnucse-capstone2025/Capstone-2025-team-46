// NetworkUtils.kt
package com.example.policyengine.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getCurrentIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
