package com.resonar.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.resonar.app.R

/**
 * Notificación de lo que suena. Media3 la arma con importancia baja y sin
 * "ongoing": en este Motorola no aparece la isla de media y el sistema mata
 * el servicio al abrir un juego. El canal va en DEFAULT y el aviso queda fijo.
 */
@UnstableApi
object PlaybackNotification {

    const val CHANNEL_ID = "resonar_playing"
    const val NOTIFICATION_ID = 1101

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val current = manager.getNotificationChannel(CHANNEL_ID)
        if (current != null && current.importance >= NotificationManager.IMPORTANCE_DEFAULT) return
        if (current != null) manager.deleteNotificationChannel(CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reproducción",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "La canción que está sonando, con play y siguiente."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun provider(context: Context): MediaNotification.Provider {
        val inner = DefaultMediaNotificationProvider.Builder(context)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        inner.setSmallIcon(R.drawable.ic_music)
        return object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                mediaButtonPreferences: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback,
            ): MediaNotification {
                val made = inner.createNotification(
                    mediaSession,
                    mediaButtonPreferences,
                    actionFactory,
                ) { updated ->
                    stamp(updated.notification)
                    onNotificationChangedCallback.onNotificationChanged(updated)
                }
                stamp(made.notification)
                return made
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: Bundle,
            ): Boolean = inner.handleCustomCommand(session, action, extras)
        }
    }

    private fun stamp(notification: Notification) {
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        notification.category = NotificationCompat.CATEGORY_TRANSPORT
        notification.visibility = Notification.VISIBILITY_PUBLIC
    }
}
