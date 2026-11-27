package com.mobileaiagent

import android.content.Context

/**
 * Build metadata that the agent does NOT need the Android SDK to read.
 * Pure Kotlin code takes this as a plain object (keeps the core unit-testable).
 */
data class AgentBuildInfo(
    val model: String = BuildConfig.DEFAULT_MAIN_MODEL,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val applicationId: String = BuildConfig.APPLICATION_ID,
    val userAgent: String = "MobileAIAgent/${BuildConfig.VERSION_NAME} (Android)",
)

object BuildInfo {
    lateinit var info: AgentBuildInfo
        private set

    fun init(): AgentBuildInfo {
        if (!::info.isInitialized) info = AgentBuildInfo()
        return info
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context): AgentBuildInfo = init()
}
