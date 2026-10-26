package br.com.guaraplay.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Serviço em primeiro plano: o Android quase nunca encerra o app enquanto ele existe. */
class KeepAliveService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "guara"
        val b: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(ch, "Guará Play", NotificationManager.IMPORTANCE_MIN))
            b = Notification.Builder(this, ch)
        } else {
            b = Notification.Builder(this)
        }
        startForeground(1, b.setSmallIcon(R.drawable.logo).setContentTitle("Guará Play em execução").build())
        if (lock == null) {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guara:sync").apply { setReferenceCounted(false); acquire() }
        }
        return START_STICKY // se o sistema encerrar, reinicia
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() { lock?.release(); super.onDestroy() }
}
