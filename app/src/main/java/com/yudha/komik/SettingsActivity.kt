package com.yudha.komik

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Layar tempat mengisi link situs komik (cukup sekali). */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Pengaturan"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val input = findViewById<EditText>(R.id.url)
        val status = findViewById<TextView>(R.id.status)
        val save = findViewById<Button>(R.id.save)

        input.setText(Prefs.baseUrl)
        if (Prefs.baseUrl.isNotEmpty()) status.text = "Sumber aktif: ${Prefs.baseUrl}"

        save.setOnClickListener {
            val base = normalize(input.text.toString())
            if (base == null) {
                status.text = "Link tidak valid. Contoh: https://namasitus.com"
                return@setOnClickListener
            }
            input.setText(base)
            Prefs.baseUrl = base
            save.isEnabled = false
            status.text = "Mengecek situs…"
            lifecycleScope.launch {
                try {
                    val (theme, n) = withContext(Dispatchers.IO) { Sources.detect(base) }
                    Prefs.theme = theme
                    val name = when (theme) {
                        "madara" -> "Madara"
                        "nhentai" -> "nhentai (API)"
                        else -> "MangaThemesia"
                    }
                    status.text = "✔ Berhasil! Sumber $name, $n komik terbaca.\nTekan tombol kembali untuk mulai membaca."
                } catch (e: Exception) {
                    status.text = "✘ ${e.message}"
                } finally {
                    save.isEnabled = true
                }
            }
        }

        findViewById<Button>(R.id.verify).setOnClickListener {
            val base = normalize(input.text.toString()) ?: Prefs.baseUrl
            if (base.isEmpty()) {
                status.text = "Isi link situs dulu."
                return@setOnClickListener
            }
            Prefs.baseUrl = base
            startActivity(Intent(this, WebViewActivity::class.java).putExtra("url", base))
        }
    }

    /** Ambil alamat utama saja, walaupun yang ditempel link halaman komik. */
    private fun normalize(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://$t"
        val u = t.toHttpUrlOrNull() ?: return null
        val port = if (u.port != HttpUrl.defaultPort(u.scheme)) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$port"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** Membuka situs di browser dalam aplikasi untuk melewati verifikasi Cloudflare. */
class WebViewActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        title = "Verifikasi situs"
        val wv = findViewById<WebView>(R.id.web)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = Net.UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = WebViewClient()
        wv.loadUrl(intent.getStringExtra("url") ?: Prefs.baseUrl)
        findViewById<Button>(R.id.done).setOnClickListener {
            CookieManager.getInstance().flush()
            finish()
        }
    }
}
