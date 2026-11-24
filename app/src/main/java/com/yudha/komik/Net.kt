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
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebCookies)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", UA)
                if (req.header("Referer") == null) {
                    val base = Prefs.baseUrl
                    if (base.isNotEmpty()) b.header("Referer", "$base/")
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

    /** Mengembalikan (isi respons, URL akhir). */
    private fun execText(req: Request): Pair<String, String> {
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val cf = r.header("Server")?.contains("cloudflare", true) == true ||
                text.contains("challenge-platform") || text.contains("Just a moment")
            if ((r.code == 403 || r.code == 503) && cf) throw CloudflareException()
            if (!r.isSuccessful) throw IOException("Gagal memuat (kode ${r.code}): ${r.request.url.encodedPath}")
            return text to r.request.url.toString()
        }
    }
}
