package br.com.guaraplay.player

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Mini servidor HTTP que só responde dentro do aparelho (127.0.0.1).
 * O player (WebView) conversa só com ele: playlist e mídias vêm dos arquivos locais,
 * com suporte a "Range" para o vídeo tocar direto do disco, sem encher a memória.
 */
class LocalServer(private val ctx: Context, private val syncer: Syncer) {
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        var tries = 0
        while (true) {
            try { ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), Engine.PORT)); break }
            catch (e: IOException) { if (++tries > 20) throw e; Thread.sleep(500) }
        }
        Thread({ while (true) { try { val s = ss.accept(); pool.execute { handle(s) } } catch (_: Exception) { } } }, "local-http")
            .apply { isDaemon = true; start() }
    }

    private fun handle(s: Socket) {
        s.use {
            try {
                s.soTimeout = 30_000
                val inp = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())
                val line = readLine(inp) ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val h = HashMap<String, String>()
                while (true) {
                    val l = readLine(inp) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                when {
                    method == "GET" && path == "/player.html" -> html(out)
                    method == "GET" && path == "/api/player" -> manifest(out)
                    method == "POST" && path == "/api/player/capture" -> forward("capture", inp, out, h)
                    method == "POST" && path == "/api/player/log" -> forward("log", inp, out, h)
                    method == "GET" && path.startsWith("/media/") -> media(out, path.removePrefix("/media/"), h["range"])
                    else -> send(out, 404, "text/plain", "nao encontrado".toByteArray())
                }
                out.flush()
            } catch (_: Exception) { }
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun head(out: OutputStream, code: Int, type: String, len: Long, extra: String = "") {
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: $len\r\nConnection: close\r\nCache-Control: no-store\r\n$extra\r\n".toByteArray())
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        head(out, code, type, body.size.toLong()); out.write(body)
    }

    private fun html(out: OutputStream) {
        val bytes = if (Store.playerHtml.exists()) Store.playerHtml.readBytes() else ctx.assets.open("player.html").use { it.readBytes() }
        send(out, 200, "text/html; charset=utf-8", bytes)
    }

    private fun manifest(out: OutputStream) {
        val o = try { if (Store.current.exists()) JSONObject(Store.current.readText()) else null } catch (_: Exception) { null }
            ?: JSONObject().put("paired", false).put("connecting", true).put("error", Engine.lastError).put("server", Config.url(ctx))
        if (syncer.capture) { o.put("capture", true); syncer.capture = false }
        send(out, 200, "application/json", o.toString().toByteArray())
    }

    /** Repassa a captura de tela do player ao servidor, usando a identidade nativa da tela. */
    private fun forward(endpoint: String, inp: InputStream, out: OutputStream, h: Map<String, String>) {
        val n = h["content-length"]?.toIntOrNull() ?: 0
        if (n <= 0 || n > 3_000_000) return send(out, 400, "application/json", "{}".toByteArray())
        val body = ByteArray(n)
        var off = 0
        while (off < n) { val r = inp.read(body, off, n - off); if (r < 0) break; off += r }
        var code = 502
        try {
            val c = URL("${Config.url(ctx)}/api/player/$endpoint?device=${Config.deviceId(ctx)}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8_000; c.readTimeout = 15_000
            c.outputStream.use { it.write(body) }
            code = c.responseCode; c.disconnect()
        } catch (_: Exception) { }
        send(out, code, "application/json", "{}".toByteArray())
    }

    private fun media(out: OutputStream, name: String, range: String?) {
        if (name.isEmpty() || name.contains("/") || name.startsWith(".")) return send(out, 404, "text/plain", ByteArray(0))
        val f = File(Store.media, name)
        if (!f.isFile) return send(out, 404, "text/plain", ByteArray(0))
        val len = f.length()
        var start = 0L
        var end = len - 1
        var code = 200
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.isEmpty() && b.isNotEmpty()) start = maxOf(0L, len - b.toLong())
            else { start = a.toLongOrNull() ?: 0L; if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1) }
            code = 206
        }
        if (len == 0L || start > end || start >= len) return head(out, 416, "text/plain", 0, "Content-Range: bytes */$len\r\n")
        val type = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"; "m4v" -> "video/x-m4v"
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        val extra = "Accept-Ranges: bytes\r\n" + if (code == 206) "Content-Range: bytes $start-$end/$len\r\n" else ""
        head(out, code, type, end - start + 1, extra)
        try {
            RandomAccessFile(f, "r").use { r ->
                r.seek(start)
                var left = end - start + 1
                val buf = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
            }
        } catch (_: IOException) { /* o player fechou a conexão */ }
    }
}
