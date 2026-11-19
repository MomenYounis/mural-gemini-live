package chat.mural.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import chat.mural.LearningRepository
import chat.mural.MainActivity
import chat.mural.R
import chat.mural.VoiceConversationService
import chat.mural.core.LearningProgressCalculator
import chat.mural.core.StudyReminderConfig
import java.time.Clock
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object StudyReminderScheduler {
    const val CHANNEL_ID = "study-reminder"
    const val NOTIFICATION_ID = 241
    const val ACTION_DAILY_REMINDER = "chat.mural.ACTION_STUDY_REMINDER"

    fun nextTriggerAtMillis(
        hour: Int,
        minute: Int = 0,
        clock: Clock = Clock.systemDefaultZone(),
        forceTomorrow: Boolean = false,
    ): Long {
        val zone = clock.zone
        val nowInstant = clock.instant()
        val today = nowInstant.atZone(zone).toLocalDate()
        val targetTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        var candidate = if (forceTomorrow) {
            today.plusDays(1).atTime(targetTime).atZone(zone).toInstant()
        } else {
            today.atTime(targetTime).atZone(zone).toInstant()
        }
        if (!forceTomorrow && !candidate.isAfter(nowInstant)) {
            candidate = today.plusDays(1).atTime(targetTime).atZone(zone).toInstant()
        }
        return candidate.toEpochMilli()
    }

    fun sync(
        context: Context,
        config: StudyReminderConfig,
        clock: Clock = Clock.systemDefaultZone(),
        forceTomorrow: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = reminderPendingIntent(appContext)
        if (!config.enabled) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val triggerAtMillis = nextTriggerAtMillis(config.hour, config.minute, clock, forceTomorrow)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    fun showNotification(context: Context, title: String, body: String) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Daily study reminder",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val openIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun reminderPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, StudyReminderReceiver::class.java).setAction(ACTION_DAILY_REMINDER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class StudyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != StudyReminderScheduler.ACTION_DAILY_REMINDER) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = runCatching { LearningRepository(appContext).loadReadOnly() }.getOrNull() ?: return@launch
                val settings = snapshot.learningSettings
                if (!settings.reminder.enabled) return@launch
                StudyReminderScheduler.sync(appContext, settings.reminder, forceTomorrow = true)
                if (VoiceConversationService.isActive()) return@launch

                val languageID = snapshot.archive.preferences.learningLanguageID
                val progress = LearningProgressCalculator.compute(
                    sessions = snapshot.archive.sessions,
                    sentenceCards = snapshot.sentenceCards,
                    activityRecords = snapshot.reviewActivityDays,
                    languageID = languageID,
                    memory = snapshot.learnerMemories[languageID],
                    dailyGoal = settings.dailyGoal,
                )
                val totalDue = LearningProgressCalculator.totalDueCards(snapshot.sentenceCards)
                val decision = LearningProgressCalculator.evaluateReminder(
                    reminderConfig = settings.reminder,
                    progress = progress,
                    totalDueCardsAllLanguages = totalDue,
                )
                if (decision.shouldNotify) {
                    StudyReminderScheduler.showNotification(appContext, decision.title, decision.body)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
