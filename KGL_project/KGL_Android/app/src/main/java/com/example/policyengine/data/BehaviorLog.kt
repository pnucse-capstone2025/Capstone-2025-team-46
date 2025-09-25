package com.example.policyengine.data

import com.google.gson.annotations.SerializedName
import java.util.*

data class BehaviorLog(
    @SerializedName("timestamp")
    val timestamp: String,

    @SerializedName("user_id")
    val userId: String,

    @SerializedName("session_id")
    val sessionId: String,

    @SerializedName("sequence_index")
    val sequenceIndex: Long = 0L,        // 추가: 시퀀스 순서 (LSTM-AE용)

    @SerializedName("action_type")
    val actionType: String,

    @SerializedName("params")
    val params: Map<String, Any>,

    @SerializedName("device_info")
    val deviceInfo: DeviceInfo,

    @SerializedName("location")
    val location: LocationInfo?
)

data class DeviceInfo(
    @SerializedName("model")
    val model: String,

    @SerializedName("os_version")
    val osVersion: String,

    @SerializedName("app_version")
    val appVersion: String,

    @SerializedName("screen_density")
    val screenDensity: Float,

    @SerializedName("network_type")
    val networkType: String
)

data class LocationInfo(
    @SerializedName("latitude")
    val latitude: Double,

    @SerializedName("longitude")
    val longitude: Double,

    @SerializedName("accuracy")
    val accuracy: Float
)

data class PolicyResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("decision")
    val decision: String, // "allow", "block", "additional_auth", "warning"

    @SerializedName("risk_score")
    val riskScore: Float,

    @SerializedName("confidence")
    val confidence: Float,

    @SerializedName("message")
    val message: String,

    @SerializedName("next_check_interval")
    val nextCheckInterval: Int
)
