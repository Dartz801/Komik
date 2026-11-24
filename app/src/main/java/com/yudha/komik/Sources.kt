package com.yudha.komik

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

data class Comic(val title: String, val url: String, val cover: String)
data class Chapter(val name: String, val url: String, val date: String)
data class Detail(val title: String, val cover: String, val synopsis: String, val chapters: List<Chapter>)

/** Data chapter yang sedang dibuka, dipakai bersama Detail -> Reader. */
object Session {
    var comicUrl = ""
    var chapters: List<Chapter> = emptyList()
}

interface Source {
    fun latest(page: Int): List<Comic>
    fun search(query: String, page: Int): List<Comic>
    fun detail(url: String): Detail
    fun pages(chapterUrl: String): List<String>
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Ambil URL gambar dari atribut lazy-load yang umum dipakai situs komik. */
internal fun Element.img(): String {
    for (a in listOf("data-lazy-src", "data-src", "data-cfsrc", "data-original", "src")) {
        val v = attr(a).trim()
        if (v.isNotEmpty() && !v.startsWith("data:")) return absUrl(a).ifEmpty { v }
    }
    val ss = attr("srcset").trim()
    if (ss.isNotEmpty()) return ss.split(",").first().trim().split(" ").first()
    return ""
}

/** Tema WordPress "MangaThemesia" — dipakai banyak situs komik Indonesia. */
class MangaThemesia(private val base: String) : Source {
    override fun latest(page: Int) = list(Net.get("$base/manga/?page=$page&order=update"))

    override fun search(query: String, page: Int) = list(Net.get("$base/page/$page/?s=${enc(query)}"))

    private fun list(doc: Document): List<Comic> =
        doc.select("div.bsx").mapNotNull { bx ->
            val a = bx.selectFirst("a[href]") ?: return@mapNotNull null
            val title = bx.selectFirst(".tt")?.text()?.takeIf { it.isNotBlank() } ?: a.attr("title")
            Comic(title.trim(), a.absUrl("href"), bx.selectFirst("img")?.img().orEmpty())
        }.distinctBy { it.url }

    override fun detail(url: String): Detail {
        val d = Net.get(url)
        val chapters = d.select("#chapterlist li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            Chapter(
                (li.selectFirst(".chapternum")?.text() ?: a.text()).trim(),
                a.absUrl("href"),
                li.selectFirst(".chapterdate")?.text().orEmpty()
            )
        }
        return Detail(
            d.selectFirst("h1.entry-title")?.text().orEmpty(),
            d.selectFirst(".thumb img, .thumbook img")?.img().orEmpty(),
            d.selectFirst(".entry-content[itemprop=description], .synp .entry-content, .entry-content")
                ?.text().orEmpty(),
            chapters
        )
    }

    override fun pages(chapterUrl: String): List<String> {
        val d = Net.get(chapterUrl)
        val fromScript = tsReader(d.html())
        if (fromScript.isNotEmpty()) return fromScript
        return d.select("#readerarea img").map { it.img() }.filter { it.isNotBlank() }.distinct()
    }

    /** Banyak situs memuat gambar lewat skrip ts_reader.run({...}). */
    private fun tsReader(html: String): List<String> {
        val m = Regex("""ts_reader\.run\((\{.*?\})\);""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        return try {
            val srcs = JSONObject(m.groupValues[1]).getJSONArray("sources")
            if (srcs.length() == 0) return emptyList()
            val imgs = srcs.getJSONObject(0).getJSONArray("images")
            (0 until imgs.length()).map { imgs.getString(it).trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Tema WordPress "Madara" (plugin WP-Manga). */
class Madara(private val base: String) : Source {
    override fun latest(page: Int) =
        list(Net.get("$base/page/$page/?s=&post_type=wp-manga&m_orderby=latest"))

    override fun search(query: String, page: Int) =
        list(Net.get("$base/page/$page/?s=${enc(query)}&post_type=wp-manga"))

    private fun list(doc: Document): List<Comic> =
        doc.select("div.c-tabs-item__content, div.page-item-detail").mapNotNull { el ->
            val a = el.selectFirst(".post-title a[href], h3 a[href], h4 a[href]")
                ?: el.selectFirst("a[href]") ?: return@mapNotNull null
            Comic(a.text().ifBlank { a.attr("title") }.trim(), a.absUrl("href"), el.selectFirst("img")?.img().orEmpty())
        }.distinctBy { it.url }

    override fun detail(url: String): Detail {
        val d = Net.get(url)
        var items = d.select("li.wp-manga-chapter")
        if (items.isEmpty()) {
            items = runCatching {
                Net.post(url.trimEnd('/') + "/ajax/chapters/").select("li.wp-manga-chapter")
            }.getOrDefault(items)
        }
        if (items.isEmpty()) {
            val id = d.selectFirst("#manga-chapters-holder")?.attr("data-id").orEmpty()
            if (id.isNotEmpty()) {
                items = runCatching {
                    Net.post(
                        "$base/wp-admin/admin-ajax.php",
                        mapOf("action" to "manga_get_chapters", "manga" to id)
                    ).select("li.wp-manga-chapter")
                }.getOrDefault(items)
            }
        }
        val chapters = items.mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            Chapter(a.text().trim(), a.absUrl("href"), li.selectFirst(".chapter-release-date")?.text().orEmpty())
        }
        val title = d.selectFirst(".post-title h1, .post-title h3")?.let { t ->
            t.select("span").remove()
            t.text()
        }.orEmpty()
        return Detail(
            title,
            d.selectFirst(".summary_image img")?.img().orEmpty(),
            d.selectFirst(".description-summary .summary__content, .summary__content, .manga-excerpt, .description-summary")
                ?.text().orEmpty(),
            chapters
        )
    }

    override fun pages(chapterUrl: String): List<String> {
        val u = if (chapterUrl.contains("?")) chapterUrl else "$chapterUrl?style=list"
        return Net.get(u).select(".reading-content img, .page-break img")
            .map { it.img() }.filter { it.isNotBlank() }.distinct()
    }
}

object Sources {
    fun current(): Source? {
        val b = Prefs.baseUrl
        if (b.isEmpty()) return null
        return if (Prefs.theme == "madara") Madara(b) else MangaThemesia(b)
    }

    /** Cek situs, kembalikan (tema, jumlah komik terbaca). */
    fun detect(base: String): Pair<String, Int> {
        val home = Net.get(base)
        val html = home.html()
        val themesia = "themesia" to MangaThemesia(base)
        val madara = "madara" to Madara(base)
        val looksMadara = home.select("div.bsx").isEmpty() &&
            (html.contains("wp-manga") || html.contains("madara"))
        val order = if (looksMadara) listOf(madara, themesia) else listOf(themesia, madara)
        var lastErr: Exception? = null
        for ((key, src) in order) {
            try {
                val n = src.latest(1).size
                if (n > 0) return key to n
            } catch (e: CloudflareException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw IOException(
            "Struktur situs belum dikenali (bukan tema MangaThemesia/Madara). " +
                "Kirim link situsnya ke Claude untuk dibuatkan parser khusus.", lastErr
        )
    }
}
