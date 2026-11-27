@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.app

import android.app.Service

object ServiceCompat {
    const val START_STICKY = 1
    const val STOP_FOREGROUND_REMOVE = 1
    const val STOP_FOREGROUND_DETACH = 2
    fun startForeground(service: Service, id: Int, notification: android.app.Notification, foregroundServiceType: Int) {}
    fun stopForeground(service: Service, flags: Int) {}
}
