package org.smartregister.fhircore.engine.util.notificationHelper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
    // Create the NotificationChannel (only for API 26+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channelName = "My Foreground Service"
        val channelDescription = "Channel for My Foreground Service"
        val channelImportance = NotificationManager.IMPORTANCE_DEFAULT

        val channel = NotificationChannel(CHANNEL_ID, channelName, channelImportance).apply {
            description = channelDescription
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // Build the notification
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle("Service Running")
        .setContentText("Your foreground service is active.")
        .setSmallIcon(R.drawable.ic_quest_logo) // Replace with your own icon
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true) // This makes the notification persistent
        .build()
}


@SuppressLint("NotificationPermission")
fun Context.updateNotification(title: String, content: String) {
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_app_logo) // Replace with your icon
        .setContentTitle(title)
        .setContentText(content)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setOngoing(true) // Keeps the notification persistent
        .build()

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, notification)
}