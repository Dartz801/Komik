package com.yudha.komik

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudflareException :
    IOException("Situs meminta verifikasi (Cloudflare). Buka Pengaturan lalu tekan \"Verifikasi di browser\".")

object Net {
    /** UA yang sama dipakai WebView & OkHttp, supaya cookie Cloudflare tetap berlaku. */
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Cookie dibagi dengan WebView (hasil verifikasi Cloudflare ikut terpakai). */
    private object WebCookies : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
            cm.flush() // simpan ke penyimpanan supaya sesi login bertahan setelah aplikasi ditutup
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebCookies)
            .connectTimeout(8, TimeUnit.SECONDS) // gagal sambung cepat ketahuan -> segera coba server cadangan
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", UA)
                if (req.header("Referer") == null) {
                    val base = Prefs.baseUrl
                    if (base.isNotEmpty()) b.header("Referer", "$base/")
                }
                // API key resmi untuk API v2 (format "nhk_..." -> "Key nhk_...")
                val key = Prefs.apiKey.trim()
                if (key.isNotEmpty() && req.url.encodedPath.startsWith("/api/v2")) {
                    b.header("Authorization", if (key.startsWith("nhk_")) "Key $key" else "User $key")
                }
                chain.proceed(b.build())
            }
            .addInterceptor { chain ->
                // Server gambar nhentai punya beberapa cermin (i, i1..i4 / t, t1..t4): coba bergantian.
                val req = chain.request()
                val m = Regex("""^([it])\d*\.nhentai\.net$""").find(req.url.host)
                    ?: return@addInterceptor chain.proceed(req)
                val p = m.groupValues[1]
                val hosts = listOf(req.url.host) +
                    listOf("", "1", "2", "3", "4").map { "$p$it.nhentai.net" }.filter { it != req.url.host }
                var lastErr: IOException? = null
                for ((i, h) in hosts.withIndex()) {
                    try {
                        val r = chain.proceed(req.newBuilder().url(req.url.newBuilder().host(h).build()).build())
                        if (r.isSuccessful || i == hosts.lastIndex) return@addInterceptor r
                        r.close()
                    } catch (e: IOException) {
                        lastErr = e
                    }
                }
                throw lastErr ?: IOException("Gambar gagal dimuat.")
            }
            .build()
    }

    fun get(url: String): Document {
        val (html, finalUrl) = execText(Request.Builder().url(url).build())
        return Jsoup.parse(html, finalUrl)
    }

    fun getText(url: String): String =
        execText(Request.Builder().url(url).header("Accept", "application/json").build()).first

    fun post(url: String, form: Map<String, String> = emptyMap()): Document {
        val body: RequestBody =
            if (form.isEmpty()) "".toRequestBody(null)
            else FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val (html, finalUrl) = execText(
            Request.Builder().url(url).post(body).header("X-Requested-With", "XMLHttpRequest").build()
        )
        return Jsoup.parse(html, finalUrl)
    }

    /** POST kosong dengan header tambahan, mengembalikan isi respons. */
    fun postText(url: String, headers: Map<String, String>): String {
        val b = Request.Builder().url(url).post("".toRequestBody(null))
        headers.forEach { (k, v) -> b.header(k, v) }
        try {
            return execText(b.build()).first
        } catch (e: IOException) {
            if (e.message?.contains("kode 403") == true || e.message?.contains("kode 401") == true) {
                throw IOException("Ditolak situs. Pastikan sudah login (menu ⋮ → Login ke situs).")
            }
            throw e
        }
    }

    /** Permintaan API (GET/POST/DELETE) yang mengembalikan isi respons. */
    fun api(method: String, url: String): String {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        when (method) {
            "POST" -> b.post("".toRequestBody(null))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        return execText(b.build()).first
    }

    /** Mengembalikan (isi respons, URL akhir). Jika dibatasi (429), tunggu sebentar lalu coba sekali lagi. */
    private fun execText(req: Request): Pair<String, String> {
        for (attempt in 0..1) {
            client.newCall(req).execute().use { r ->
                if (r.code == 429 && attempt == 0) {
                    val wait = r.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 15L) ?: 5L
                    Thread.sleep(wait * 1000)
                    return@use
                }
                val text = r.body?.string().orEmpty()
                val cf = r.header("Server")?.contains("cloudflare", true) == true ||
                    text.contains("challenge-platform") || text.contains("Just a moment")
                if ((r.code == 403 || r.code == 503) && cf) throw CloudflareException()
                if (r.code == 429) throw IOException("Terlalu banyak permintaan ke situs. Tunggu sebentar lalu coba lagi.")
                if (r.code == 401) throw IOException("API key ditolak situs. Periksa API key di Pengaturan.")
                if (!r.isSuccessful) throw IOException("Gagal memuat (kode ${r.code}): ${r.request.url.encodedPath}")
                return text to r.request.url.toString()
            }
        }
        throw IOException("Gagal memuat.")
    }
}
