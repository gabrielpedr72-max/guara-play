package br.com.guaraplay.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mantém o aparelho em dia com o servidor:
 *  1. busca a playlist; 2. baixa TODAS as mídias (retomando de onde parou);
 *  3. só depois de tudo baixado troca a playlist que está tocando.
 * Sem internet, nada disso interfere: a playlist atual continua tocando dos arquivos locais.
 */
class Syncer(private val ctx: Context) : Runnable {
    @Volatile var capture = false
    @Volatile private var downloading = false
    private var htmlAt = 0L

    /** Chamado a cada 20 s por outra thread: avisa o servidor que a tela está viva mesmo durante um download longo. */
    fun heartbeat() {
        val base = Config.url(ctx)
        if (downloading && base.isNotBlank()) get("$base/api/player?device=${Config.deviceId(ctx)}&p=Android")
    }

    override fun run() {
        while (true) {
            try { cycle() } catch (e: Throwable) { Engine.lastError = "Resposta inesperada do servidor: ${e.javaClass.simpleName}" }
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { }
        }
    }

    private fun get(url: String): String? {
        var open: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            open = c
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() }
            else { Engine.lastError = "O servidor respondeu HTTP ${c.responseCode} em $url"; null }
        } catch (e: Exception) { Engine.lastError = "${e.javaClass.simpleName}: ${e.message} ($url)"; null } finally { open?.disconnect() }
    }

    private fun urlsOf(m: JSONObject): List<String> {
        val a = m.optJSONArray("items") ?: return emptyList()
        val r = ArrayList<String>()
        for (i in 0 until a.length()) { val u = a.getJSONObject(i).optString("url"); if (u.startsWith("/media/")) r.add(u) }
        return r.distinct()
    }

    private fun readCurrent(): JSONObject? =
        try { if (Store.current.exists()) JSONObject(Store.current.readText()) else null } catch (_: Exception) { null }

    private fun cycle() {
        val base = Config.url(ctx)
        if (base.isBlank()) { Engine.lastError = "Endereço do servidor não configurado"; return }
        val txt = get("$base/api/player?device=${Config.deviceId(ctx)}&p=Android") ?: return // sem rede: segue tocando
        val m = JSONObject(txt)
        Engine.lastSyncOk = System.currentTimeMillis()
        Engine.lastError = ""
        capture = m.optBoolean("capture")
        refreshHtml(base)

        if (!m.optBoolean("paired")) {
            if (Config.paired(ctx)) { // a tela foi removida no painel: volta a pedir pareamento com código novo
                Config.newIdentity(ctx); Store.current.delete(); wipeMedia(); return
            }
            Store.writeAtomic(Store.current, txt) // mostra o código
            return
        }
        Config.setPaired(ctx, true)

        val cur = readCurrent()
        if (cur?.optString("version") == m.optString("version")) { cleanup(m, cur); return }

        val urls = urlsOf(m)
        val showing = cur != null && cur.optBoolean("paired") && cur.optString("version") != "dl"
        var done = 0
        downloading = true
        try {
        for (u in urls) {
            if (!ensure(base, u)) { if (!showing) progress(m, done, urls.size); return } // tenta de novo no próximo ciclo
            done++
            if (!showing) progress(m, done, urls.size)
        }
        } finally { downloading = false }
        Store.writeAtomic(Store.current, txt) // tudo baixado: troca a playlist
        cleanup(m, null)
    }

    private fun progress(m: JSONObject, done: Int, total: Int) {
        Store.writeAtomic(Store.current, JSONObject().put("paired", true).put("version", "dl").put("items", JSONArray())
            .put("rotation", m.optInt("rotation")).put("downloading", "$done de $total arquivos").toString())
    }

    /** Baixa um arquivo (retomando um download interrompido). */
    private fun ensure(base: String, url: String): Boolean {
        val name = url.substringAfterLast('/')
        if (name.isEmpty() || name.contains("..")) return false
        val dest = File(Store.media, name)
        if (dest.exists()) return true
        val part = File(Store.media, "$name.part")
        var open: HttpURLConnection? = null
        try {
            val have = if (part.exists()) part.length() else 0L
            val c = URL(base + url).openConnection() as HttpURLConnection
            open = c
            c.connectTimeout = 8_000; c.readTimeout = 20_000
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) { part.delete(); return false }
            if (code != 200 && code != 206) return false
            val append = code == 206
            val total = if (append) c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                        else c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (total > 0 && Store.media.usableSpace < total - (if (append) have else 0L) + 100_000_000L) return false // sem espaço
            FileOutputStream(part, append).use { o -> c.inputStream.use { it.copyTo(o) } }
            if (total > 0 && part.length() != total) return false
            return part.renameTo(dest)
        } catch (_: Exception) {
            return false // o .part fica guardado para retomar depois
        } finally { open?.disconnect() }
    }

    private fun cleanup(m: JSONObject, cur: JSONObject?) {
        val keep = HashSet<String>()
        (urlsOf(m) + (cur?.let { urlsOf(it) } ?: emptyList())).forEach { keep.add(it.substringAfterLast('/')) }
        Store.media.listFiles()?.forEach { f -> if (f.name.removeSuffix(".part") !in keep) f.delete() }
    }

    private fun wipeMedia() { Store.media.listFiles()?.forEach { it.delete() } }

    private fun refreshHtml(base: String) {
        if (System.currentTimeMillis() - htmlAt < 300_000 && Store.playerHtml.exists()) return
        val t = get("$base/player.html") ?: return
        if (t.length > 1000 && t.contains("<script")) { Store.writeAtomic(Store.playerHtml, t); htmlAt = System.currentTimeMillis() }
    }
}
