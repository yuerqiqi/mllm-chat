package org.saltedfish.chatbot

import android.content.Context

enum class RouteDestination {
    LOCAL_MODEL,
    CLOUD_API
}

object AppConfig {
    private const val PREFS_NAME = "chatbot_settings"
    private const val KEY_USE_LOCAL_MODEL = "use_local_model"
    private const val KEY_ENABLE_HALLUCINATION_DETECTION = "enable_hallucination_detection"

    var useLocalModel: Boolean = true
    var enableHallucinationDetection: Boolean = false

    var apiKey: String = ""
    var cloudApiUrl: String = "https://api.deepseek.com/chat/completions"
    var cloudModelName: String = "deepseek-chat"
    var selectedTextModelId: String? = null
    var selectedVisionModelId: String? = null

    const val LOCAL_API_URL = "http://127.0.0.1:8080/v1/chat/completions"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        useLocalModel = prefs.getBoolean(KEY_USE_LOCAL_MODEL, useLocalModel)
        enableHallucinationDetection = prefs.getBoolean(
            KEY_ENABLE_HALLUCINATION_DETECTION,
            enableHallucinationDetection
        )
    }

    fun persist(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_LOCAL_MODEL, useLocalModel)
            .putBoolean(KEY_ENABLE_HALLUCINATION_DETECTION, enableHallucinationDetection)
            .apply()
    }

    fun getCurrentApiUrl(): String {
        return if (useLocalModel) LOCAL_API_URL else cloudApiUrl
    }

    fun determineRoute(prompt: String, hasImage: Boolean): RouteDestination {
        if (!useLocalModel) return RouteDestination.CLOUD_API
        return RouteDestination.LOCAL_MODEL
    }
}