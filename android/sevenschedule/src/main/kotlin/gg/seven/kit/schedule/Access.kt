package gg.seven.kit.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * What the phone currently lets an app deliver. Each app turns these into its own words; none of
 * them is proof that a particular alert will appear (Focus, OEM battery rules and the user can
 * still intervene).
 */
class ScheduleAccess(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Whether exact alarms are allowed (always before Android 12). */
    val exactAllowed: Boolean
        get() =
            Build.VERSION.SDK_INT < 31 ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Whether the app's notifications are on at all. */
    val notificationsAllowed: Boolean
        get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Whether the runtime notification permission is granted (always before Android 13). */
    val postPermissionGranted: Boolean
        get() =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

    /** Whether a full-screen intent may show over the lock screen (always before Android 14). */
    val fullScreenAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()

    /**
     * Whether the user left [channel] audible: off when they set it to "None". A channel not
     * created yet counts as on, since it is created with the app's default importance.
     */
    fun channelEnabled(channel: String): Boolean =
        (notifications.getNotificationChannel(channel)?.importance
            ?: NotificationManager.IMPORTANCE_DEFAULT) != NotificationManager.IMPORTANCE_NONE

    /** Notifications are on and [channel] is not muted. */
    fun canNotify(channel: String): Boolean = notificationsAllowed && channelEnabled(channel)
}
