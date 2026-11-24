package com.yudha.komik

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

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

        val keyInput = findViewById<EditText>(R.id.apiKey)
        keyInput.setText(Prefs.apiKey)
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
            Prefs.apiKey = keyInput.text.toString()
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
                    val keyInfo = if (Prefs.apiKey.isNotBlank()) "\nAPI key aktif." else ""
                    status.text = "✔ Berhasil! Sumber $name, $n komik terbaca.$keyInfo\nTekan tombol kembali untuk mulai membaca."
                } catch (e: Exception) {
                    status.text = "✘ ${e.message}"
                } finally {
                    save.isEnabled = true
                }
            }
        }

        setupCache()

        // Tes: ambil satu sampul dan tampilkan jawaban server-nya.
        findViewById<Button>(R.id.testImg).setOnClickListener {
            status.text = "Mengetes gambar…"
            lifecycleScope.launch {
                status.text = withContext(Dispatchers.IO) { testImage() }
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

    /** Ambil satu sampul, lalu coba sambung ke tiap server gambar dan laporkan hasilnya. */
    private fun testImage(): String {
        val src = Sources.current() ?: return "Isi link situs dulu."
        val cover = try {
            src.latest(1).firstOrNull()?.cover.orEmpty()
        } catch (e: Exception) {
            return "Daftar komik gagal dimuat: ${e.message}"
        }
        val url = cover.toHttpUrlOrNull() ?: return "Alamat sampul tidak valid: \"$cover\""
        val plain = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val m = Regex("""^([it])\d*\.nhentai\.net$""").find(url.host)
        val hosts = if (m == null) listOf(url.host) else {
            val p = m.groupValues[1]
            (listOf(url.host) + listOf("", "1", "2", "3", "4").map { "$p$it.nhentai.net" }).distinct()
        }
        val sb = StringBuilder("Hasil tes server gambar:\n")
        for (h in hosts) {
            val line = try {
                val req = Request.Builder().url(url.newBuilder().host(h).build())
                    .header("User-Agent", Net.UA).header("Referer", Prefs.baseUrl + "/").build()
                plain.newCall(req).execute().use { r -> "kode ${r.code}" + if (r.isSuccessful) " ✔" else "" }
            } catch (e: Exception) {
                "✘ ${e.javaClass.simpleName}"
            }
            sb.append("$h : $line\n")
        }
        return sb.toString().trimEnd()
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun setupCache() {
        val usage = findViewById<TextView>(R.id.cacheUsage)
        val group = findViewById<RadioGroup>(R.id.cacheSize)
        val ids = mapOf(200 to R.id.cache200, 500 to R.id.cache500, 1024 to R.id.cache1024)
        group.check(ids[Prefs.cacheMb] ?: R.id.cache500)

        fun refresh() {
            val used = (imageLoader.diskCache?.size ?: 0L) / (1024 * 1024)
            usage.text = "Terpakai: $used MB dari ${Prefs.cacheMb} MB"
        }
        refresh()

        group.setOnCheckedChangeListener { _, checkedId ->
            val mb = ids.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            if (mb == Prefs.cacheMb) return@setOnCheckedChangeListener
            Prefs.cacheMb = mb
            refresh()
            Toast.makeText(this, "Batas cache $mb MB berlaku setelah aplikasi dibuka ulang.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.clearCache).setOnClickListener {
            imageLoader.memoryCache?.clear()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
                refresh()
                Toast.makeText(this@SettingsActivity, "Cache gambar dihapus.", Toast.LENGTH_SHORT).show()
            }
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
        title = intent.getStringExtra("title") ?: "Verifikasi situs"
        intent.getStringExtra("hint")?.let { findViewById<TextView>(R.id.hint).text = it }
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
