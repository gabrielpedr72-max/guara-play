package br.com.guaraplay.player

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

class GuaraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Se o app cair por qualquer erro, agenda a reabertura em 3 segundos.
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val pi = PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                )
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 3_000, pi)
            } catch (_: Throwable) { }
            if (old != null) old.uncaughtException(t, e) else exitProcess(2)
        }
        Engine.start(this)
    }
}
