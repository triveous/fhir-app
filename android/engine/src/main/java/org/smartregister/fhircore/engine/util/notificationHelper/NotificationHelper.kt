package org.smartregister.fhircore.engine.util.notificationHelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.smartregister.fhircore.engine.R

/**
 * Created by Jeetesh Surana.
 */

object NotificationHelper {
    const val CHANNEL_NAME = "Aarogya Aarohan Notification Channel"
}


const val CHANNEL_ID = "my_foreground_service_channel"
const val NOTIFICATION_ID = 1

fun createNotification(context: Context): Notification {
    val channelName = "My Foreground Service"
    val channelDescription = "Channel for My Foreground Service"
    val channelImportance = NotificationManager.IMPORTANCE_DEFAULT

    val channel = NotificationChannel(CHANNEL_ID, channelName, channelImportance).apply {
        description = channelDescription
    }

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)

    // Build the notification
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.appname))
        .setContentText(context.getString(R.string.syncing))
        .setSmallIcon(R.drawable.ic_quest_logo) // Replace with your own icon
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true) // This makes the notification persistent
        .build()
}

fun isNotificationRunning(context: Context, notificationId: Int): Boolean {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val activeNotifications: Array<StatusBarNotification> = notificationManager.activeNotifications
    for (notification in activeNotifications) {
        if (notification.id == notificationId) {
            return true
        }
    }
    return false
}