package br.com.guaraplay.player

import android.content.Context
import java.io.File

/** Arquivos guardados no aparelho: mídias, playlist atual e página do player. */
object Store {
    lateinit var root: File
    lateinit var media: File
    val current get() = File(root, "current.json")
    val playerHtml get() = File(root, "player.html")

    fun init(c: Context) {
        root = File(c.filesDir, "guara").apply { mkdirs() }
        media = File(root, "media").apply { mkdirs() }
    }

    /** Escreve sem nunca deixar um arquivo pela metade. */
    fun writeAtomic(f: File, text: String) {
        val t = File(f.path + ".tmp")
        t.writeText(text)
        t.renameTo(f)
    }
}
