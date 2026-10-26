package br.com.guaraplay.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ao ligar o aparelho (ou depois de atualizar o app), inicia o motor e abre o player. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Engine.ensureService(context)
        try { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}
