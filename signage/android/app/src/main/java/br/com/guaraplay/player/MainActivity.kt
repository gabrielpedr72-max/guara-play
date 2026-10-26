package br.com.guaraplay.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var backCount = 0
    private var backAt = 0L
    private var pinFails = 0
    private var pinBlockedUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        Engine.ensureService(this)
        askOverlayOnce()
        if (Config.url(this).isBlank()) showConfig(first = true) else startPlayer()
    }

    override fun onResume() {
        super.onResume()
        Engine.setVisible(true)
        Kiosk.enforce(this)
        hideBars()
    }

    override fun onPause() { Engine.setVisible(false); super.onPause() }

    /** O player roda dentro de um WebView, falando só com o servidor interno do próprio app. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startPlayer() {
        web?.let { root.removeView(it); it.destroy() }
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
        }
        val target = "http://127.0.0.1:${Engine.PORT}/player.html?native=1"
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) ui.postDelayed({ w.loadUrl(target) }, 5_000)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { startPlayer() }
                return true
            }
        }
        w.loadUrl(target)
        root.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        web = w
    }

    private fun askOverlayOnce() {
        val p = getSharedPreferences("guara", 0)
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this) && !p.getBoolean("asked", false)) {
            p.edit().putBoolean("asked", true).apply()
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
        }
    }

    // ---------- manutenção (protegida por PIN) ----------

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun maintenance() {
        if (System.currentTimeMillis() < pinBlockedUntil) { toast("Muitas tentativas. Aguarde alguns minutos."); return }
        val pin = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("Modo manutenção").setView(pin)
            .setPositiveButton("Entrar") { _, _ ->
                if (pin.text.toString() == BuildConfig.ADMIN_PIN) { pinFails = 0; maintenanceMenu() }
                else { if (++pinFails >= 5) { pinFails = 0; pinBlockedUntil = System.currentTimeMillis() + 5 * 60_000 }; toast("PIN incorreto") }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun maintenanceMenu() {
        val items = arrayOf("Informações", "Endereço do servidor", "Configurações do Android (Wi-Fi…)", "Desativar modo administrador")
        AlertDialog.Builder(this).setTitle("Manutenção")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showInfo()
                    1 -> showConfig(false)
                    2 -> openAndroidSettings()
                    3 -> AlertDialog.Builder(this).setTitle("Desativar modo administrador?")
                        .setMessage("O aparelho volta ao normal: poderá sair do app, desinstalar e restaurar de fábrica.")
                        .setPositiveButton("Desativar") { _, _ -> Kiosk.disable(this); toast("Modo administrador desativado") }
                        .setNegativeButton("Cancelar", null).show()
                    else -> {}
                }
            }
            .setNegativeButton("Fechar", null).show()
    }

    private fun showInfo() {
        val files = Store.media.listFiles()?.filter { !it.name.endsWith(".part") } ?: emptyList()
        val mb = files.sumOf { it.length() } / 1_000_000
        AlertDialog.Builder(this).setTitle("Informações")
            .setMessage(
                "Servidor: ${Config.url(this)}\n" +
                "ID da tela: ${Config.deviceId(this).take(8)}\n" +
                "Pareada: ${if (Config.paired(this)) "sim" else "não"}\n" +
                "Última sincronização: ${Engine.lastSyncText()}\n" +
                "Arquivos no aparelho: ${files.size} ($mb MB)\n" +
                "Modo administrador: ${if (Kiosk.isOwner(this)) "ativo" else "inativo"}\n" +
                "Versão: ${BuildConfig.VERSION_NAME}"
            ).setPositiveButton("OK", null).show()
    }

    private fun openAndroidSettings() {
        Engine.watchdogPausedUntil = System.currentTimeMillis() + 10 * 60_000 // dá 10 min sem o vigia
        Kiosk.unlock(this)
        try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast("Não foi possível abrir as configurações.") }
    }

    private fun showConfig(first: Boolean) {
        val input = EditText(this).apply {
            setText(Config.url(this@MainActivity))
            hint = "http://192.168.0.10:3000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val b = AlertDialog.Builder(this).setTitle("Endereço do servidor Guará Play").setView(input).setCancelable(!first)
            .setPositiveButton("Salvar") { _, _ ->
                val u = Config.normalize(input.text.toString())
                if (u.isNotEmpty()) { Config.save(this, u); startPlayer() } else if (first) showConfig(true)
            }
        if (!first) b.setNegativeButton("Cancelar", null)
        b.show()
    }

    // ---------- tela cheia e teclas ----------

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // Menu no controle, ou "Voltar" 5 vezes seguidas, abre a manutenção (pede PIN).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { maintenance(); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            backCount = if (now - backAt < 3000) backCount + 1 else 1
            backAt = now
            if (backCount >= 5) { backCount = 0; maintenance() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web?.destroy()
        super.onDestroy()
    }
}
