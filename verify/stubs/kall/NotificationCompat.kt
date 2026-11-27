@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

class NotificationCompat {
    companion object {
        const val CATEGORY_PROGRESS = "progress"
        const val PRIORITY_LOW = -1
        const val PRIORITY_DEFAULT = 0
        const val FOREGROUND_SERVICE_IMMEDIATE = 1
        const val FOREGROUND_SERVICE_DEFAULT = 0
        const val VISIBILITY_PUBLIC = 1
    }

    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setSubText(text: CharSequence?): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setCategory(category: String?): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun setForegroundServiceBehavior(behavior: Int): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: PendingIntent?): Builder = this
        fun setVisibility(visibility: Int): Builder = this
        fun setStyle(style: Any?): Builder = this
        fun build(): Notification = Notification()
    }

    class BigTextStyle {
        fun bigText(text: CharSequence?): BigTextStyle = this
        fun setBigContentTitle(title: CharSequence?): BigTextStyle = this
    }
}
