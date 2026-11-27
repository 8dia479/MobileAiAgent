@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.app

import android.app.Notification
import android.content.Context

class NotificationManagerCompat private constructor() {
    fun notify(id: Int, notification: Notification) {}
    fun cancel(id: Int) {}
    fun areNotificationsEnabled(): Boolean = true
    companion object {
        fun from(context: Context): NotificationManagerCompat = NotificationManagerCompat()
    }
}
