package com.notessync.app.reminders

import com.notessync.app.i18n.uiText

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.notessync.app.MainActivity

class ReminderReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // canNotify checks runtime permission and app/channel state.
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { uiText("Заметка") }
        val isTimer = intent.getBooleanExtra(EXTRA_IS_TIMER, false)

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (isTimer) uiText("Время вышло") else uiText("Напоминание"))
            .setContentText(title)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        if (ReminderScheduler.canNotify(context)) {
            val id = (noteId xor (noteId ushr 32)).toInt()
            NotificationManagerCompat.from(context).notify(
                if (isTimer) id xor TIMER_NOTIFICATION_SALT else id,
                notification
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TITLE = "note_title"
        const val EXTRA_IS_TIMER = "is_timer"
        private const val TIMER_NOTIFICATION_SALT = 0x51A7E3
    }
}
