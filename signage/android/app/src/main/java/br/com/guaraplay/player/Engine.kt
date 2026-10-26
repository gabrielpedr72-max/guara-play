package br.com.guaraplay.player

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build

/** Motor do app: roda mesmo sem a tela aberta (sincronização, servidor interno e vigia). */
object Engine {
    const val PORT = 38765
    lateinit var app: Application
    @Volatile var activityVisible = false
    @Volatile var lastVisibleAt = System.currentTimeMillis()
    @Volatile var lastSyncOk = 0L
    @Volatile var watchdogPausedUntil = 0L
    private var started = false

    @Synchronized
    fun start(a: Application) {
        if (started) return
        started = true
        app = a
        Store.init(a)
        // Enquanto a tela não estiver pareada, cada abertura do app gera um código novo.
        if (!Config.paired(a)) { Config.newIdentity(a); Store.current.delete() }
        val syncer = Syncer(a)
        try { LocalServer(a, syncer).start() } catch (_: Exception) { }
        Thread(syncer, "sync").apply { isDaemon = true; start() }
        Thread({ while (true) { try { Thread.sleep(20_000); syncer.heartbeat() } catch (_: Throwable) { } } }, "heartbeat").apply { isDaemon = true; start() }
        Thread({ watchdog() }, "watchdog").apply { isDaemon = true; start() }
    }

    fun setVisible(v: Boolean) { activityVisible = v; if (!v) lastVisibleAt = System.currentTimeMillis() }

    /** Se o player sair da frente por mais de 20 s, traz de volta. */
    private fun watchdog() {
        while (true) {
            try {
                Thread.sleep(15_000)
                val now = System.currentTimeMillis()
                if (!activityVisible && now - lastVisibleAt > 20_000 && now > watchdogPausedUntil && Config.url(app).isNotBlank()) {
                    app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (_: Throwable) { }
        }
    }

    fun ensureService(c: Context) {
        val i = Intent(c, KeepAliveService::class.java)
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (_: Exception) { }
    }

    fun lastSyncText(): String {
        if (lastSyncOk == 0L) return "ainda não conectou"
        val s = (System.currentTimeMillis() - lastSyncOk) / 1000
        return if (s < 60) "há ${s}s" else if (s < 3600) "há ${s / 60} min" else "há ${s / 3600} h"
    }
}
