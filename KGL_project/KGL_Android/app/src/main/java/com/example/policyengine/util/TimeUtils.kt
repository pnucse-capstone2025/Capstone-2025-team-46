package com.example.policyengine.util

import java.text.SimpleDateFormat
import java.util.*

object TimeUtils {
    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun getCurrentISO8601(): String {
        return iso8601Format.format(Date())
    }

    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis()
    }
}