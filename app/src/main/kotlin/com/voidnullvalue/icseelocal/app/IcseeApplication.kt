package com.voidnullvalue.icseelocal.app

import android.app.Application
import com.voidnullvalue.icseelocal.session.CameraSessionRegistry

class IcseeApplication : Application() {
    /**
     * The single, app-wide owner of DVRIP control sessions. Lives here (not in any
     * ViewModel) precisely so one session per camera is shared across every
     * Activity-scoped screen family, and its login-rate ceiling can't be reset by a
     * screen coming or going. See [CameraSessionRegistry].
     */
    val sessionRegistry: CameraSessionRegistry by lazy { CameraSessionRegistry() }
}
