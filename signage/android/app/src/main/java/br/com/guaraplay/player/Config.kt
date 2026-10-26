package br.com.guaraplay.player

import android.content.Context
import java.util.UUID

/** Configurações guardadas no aparelho. */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("guara", 0)

    fun url(c: Context): String = p(c).getString("url", null) ?: BuildConfig.DEFAULT_SERVER_URL
    fun save(c: Context, raw: String) { p(c).edit().putString("url", normalize(raw)).apply() }

    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/')
    }

    /** Identidade da tela no servidor. */
    fun deviceId(c: Context): String {
        var d = p(c).getString("dev", null)
        if (d == null) { d = UUID.randomUUID().toString(); p(c).edit().putString("dev", d).apply() }
        return d
    }
    fun paired(c: Context) = p(c).getBoolean("paired", false)
    fun setPaired(c: Context, v: Boolean) { p(c).edit().putBoolean("paired", v).apply() }

    /** Nova identidade (e, portanto, novo código de pareamento). */
    fun newIdentity(c: Context) { p(c).edit().putString("dev", UUID.randomUUID().toString()).putBoolean("paired", false).apply() }
}
