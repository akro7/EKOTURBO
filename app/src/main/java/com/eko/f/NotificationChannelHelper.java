package com.eko.f;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * Helper to create Android O+ notification channels.
 * Safe to call multiple times — Android ignores duplicate channel creation.
 */
public final class NotificationChannelHelper {

    private NotificationChannelHelper() {}

    /**
     * Creates a notification channel if running on Android O (API 26) or higher.
     *
     * @param nm        The NotificationManager system service
     * @param channelId Unique channel ID
     * @param name      User-visible channel name shown in Settings
     */
    public static void createChannel(NotificationManager nm, String channelId, String name) {
        if (nm == null || channelId == null || channelId.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        try {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    name != null ? name : channelId,
                    NotificationManager.IMPORTANCE_LOW   // Low = no sound, shows in shade
            );
            channel.setDescription(name);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {}
    }
}
