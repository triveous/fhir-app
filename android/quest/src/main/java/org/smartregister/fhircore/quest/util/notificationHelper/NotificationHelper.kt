package org.smartregister.fhircore.quest.util.notificationHelper

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.engine.util.notificationHelper.NotificationHelper.CHANNEL_NAME

/**
 * Created by Jeetesh Surana.
 */

object NotificationHelper {
    const val CHANNEL_NAME = "Aarogya Aarohan Notification Channel"
}

// Extension function to create and get a notification channel
@SuppressLint("ObsoleteSdkInt")
fun NotificationManager.createNotificationChannel(channelId: String, channelName: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, channelName, importance)
        createNotificationChannel(channel)
    }
}


// Extension function to show a notification
@SuppressLint("NotificationPermission")
fun Context.showNotification(
    channelId: String,
    notificationId: Int,
    title: String,
    content: String,
    importance: Int = NotificationManager.IMPORTANCE_HIGH,
    askNotificationPermission:(Boolean,Boolean)->Unit
): NotificationCompat.Builder {
    val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java) as NotificationManager

    notificationManager.createNotificationChannel(channelId, CHANNEL_NAME, importance)

    val builder = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_logo)
        .setContentTitle(title)
        .setContentText(content)
        .setPriority(importance)

    val hasNotificationPermission = hasNotificationPermission()
    val areNotificationsEnabled = areNotificationsEnabled()
    if (hasNotificationPermission && areNotificationsEnabled) {
        notificationManager.notify(notificationId, builder.build())
    } else {
        askNotificationPermission(hasNotificationPermission,areNotificationsEnabled)
    }
    return builder
}

// Extension function to update an existing notification
@SuppressLint("NotificationPermission")
fun Context.updateNotification(
    channelId: String,
    notificationId: Int,
    title: String,
    content: String,
    askNotificationPermission:(Boolean,Boolean)->Unit
) {
    val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java) as NotificationManager

    val builder = NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.drawable.ic_logo)
        .setContentTitle(title)
        .setContentText(content)

    val hasNotificationPermission = hasNotificationPermission()
    val areNotificationsEnabled = areNotificationsEnabled()
    if (hasNotificationPermission && areNotificationsEnabled) {
        notificationManager.notify(notificationId, builder.build())
    } else {
        askNotificationPermission(hasNotificationPermission,areNotificationsEnabled)
    }
}

// Extension function to cancel a notification
fun Context.cancelNotification(notificationId: Int) {
    val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java) as NotificationManager
    notificationManager.cancel(notificationId)
}


// Extension function to check notification permission
fun Context.hasNotificationPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true // Permission is automatically granted on versions below Android 13
    }
}


// Extension function to check if notifications are enabled
fun Context.areNotificationsEnabled(): Boolean {
    val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java) as NotificationManager
    return notificationManager.areNotificationsEnabled()
}