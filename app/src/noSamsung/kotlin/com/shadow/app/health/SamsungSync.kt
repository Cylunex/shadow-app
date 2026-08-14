package com.shadow.app.health

import android.app.Activity
import android.content.Context

/** Build-time fallback used when the proprietary Samsung Health SDK AAR is absent. */
object SamsungSync {
    const val PREF_ENABLED = "samsung_sync_enabled"

    @JvmStatic
    fun isAvailable(): Boolean = false

    @JvmStatic
    fun enable(@Suppress("UNUSED_PARAMETER") activity: Activity) = Unit

    @JvmStatic
    fun disable(@Suppress("UNUSED_PARAMETER") ctx: Context) = Unit

    @JvmStatic
    fun schedule(@Suppress("UNUSED_PARAMETER") ctx: Context) = Unit

    @JvmStatic
    fun syncNow(@Suppress("UNUSED_PARAMETER") ctx: Context) = Unit
}
