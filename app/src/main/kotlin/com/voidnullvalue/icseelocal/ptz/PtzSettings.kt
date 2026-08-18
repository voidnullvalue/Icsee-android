package com.voidnullvalue.icseelocal.ptz

import android.content.Context
import android.content.SharedPreferences

/** App-wide local preference for reversing user-facing PTZ directions. */
object PtzSettings {
    private const val PREFS_NAME = "ui_settings"
    private const val KEY_INVERT_PTZ = "invert_ptz"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    var inverted: Boolean = false
        private set

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val loaded = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            inverted = loaded.getBoolean(KEY_INVERT_PTZ, false)
            prefs = loaded
        }
    }

    fun isInverted(context: Context): Boolean {
        initialize(context)
        return inverted
    }

    fun setInverted(context: Context, value: Boolean) {
        initialize(context)
        inverted = value
        prefs?.edit()?.putBoolean(KEY_INVERT_PTZ, value)?.apply()
    }
}
