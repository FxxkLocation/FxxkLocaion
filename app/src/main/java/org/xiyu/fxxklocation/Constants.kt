package org.xiyu.fxxklocation

import android.util.Log
import de.robv.android.xposed.XposedBridge

internal const val TAG = "FxxkLocation"
internal const val TARGET_PKG = "com.lerist.fakelocation"

// ---- Obfuscated class names (verified via runtime logs) ----
internal const val CLS_BLACKLIST    = "androidx.appcompat.view.widget.\u03AC"
internal const val CLS_CONFIG       = "androidx.appcompat.view.widget.\u0E1E"
internal const val CLS_USER_SESSION = "androidx.appcompat.view.widget.\u015E\u085B\u015E$\u0DBD"
internal const val CLS_USER_MODEL   = "androidx.appcompat.view.widget.qc2"
internal const val CLS_ENFORCER     = "androidx.appcompat.view.widget.\u01C5\u036C\u01C5"
internal const val CLS_SERVICE      = "androidx.appcompat.view.widget.\u0DC4\u036B\u0DC4"
internal const val CLS_ROUTE_CTRL   = "androidx.appcompat.view.widget.\u02A9\u0368\u02A9"
internal const val CLS_AIDL_PROXY   = "androidx.appcompat.view.widget.\u0268\u01DC\u0268$\u0D88$\u0D88"
internal const val CLS_MOCK_CTRL    = "androidx.appcompat.view.widget.\u0E0F\u036D\u0E0F"
internal const val CLS_DEFAULTS     = "androidx.appcompat.view.widget.\u0283\u05E5"
internal const val CLS_JED_BL       = "androidx.appcompat.view.widget.gt0"
internal const val CLS_ENF_LOADER   = "androidx.appcompat.view.widget.ft0"
internal const val CLS_TOKEN_CHECKER = "androidx.appcompat.view.widget.tc2"
internal const val CLS_PRO_VALIDATOR  = "androidx.appcompat.view.widget.pc2"
internal const val CLS_DEBUG_DETECT   = "androidx.appcompat.view.widget.cp2"
internal const val CLS_ANTI_DEBUG     = "androidx.appcompat.view.widget.\u06BD"

// Mode0Binder (C8160) — routes MODE_0 mock to service_fl_ml
internal const val CLS_MODE0_BINDER   = "androidx.appcompat.view.widget.\u0C08\u0369\u0C08"  // C8160 ఈͩఈ

// FL-Xposed XP mode classes
internal const val CLS_XP_AP2         = "androidx.appcompat.view.widget.ap2"
internal const val CLS_XP_READY       = "androidx.appcompat.view.widget.\u0582\u0430\u0582"  // C5039

internal const val FL_AIDL_DESCRIPTOR = "com.lerist.aidl.fakelocation.IMockLocationManager"
internal const val FL_XP_DESCRIPTOR   = "com.lerist.aidl.fakelocation.IXPServer"

internal val DUMMY_BLACKLIST: List<String> = List(15) { "x${it}.nonexistent.fake.app" }

internal fun filterBlacklist(original: List<String>?): List<String> {
    val list = ArrayList(original ?: DUMMY_BLACKLIST)
    try {
        val f = java.io.File("/data/local/tmp/fl_whitelist.txt")
        if (f.exists() && f.canRead()) {
            val whitelisted = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (whitelisted.isNotEmpty()) {
                list.removeAll(whitelisted)
                log("Removed ${whitelisted.size} packages from FL blacklist based on /data/local/tmp/fl_whitelist.txt")
            }
        }
    } catch (e: Exception) {
        log("filterBlacklist read error: $e")
    }
    return list
}

internal fun log(msg: String) {
    Log.d(TAG, msg)
    XposedBridge.log("$TAG: $msg")
}
