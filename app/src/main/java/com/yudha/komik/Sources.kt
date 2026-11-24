package com.yudha.komik

import org.json.JSONArray
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

/**
 * nhentai: daftar & pencarian dibaca dari halaman web (bukan API, karena API daftar sudah 404).
 * Data galeri diambil dari JSON yang tertanam di halaman /g/<id>/, dengan cadangan
 * API lama dan cadangan terakhir membaca thumbnail di halaman.
 * Satu galeri = satu "chapter".
 */
class Nhentai(private val base: String) : Source {
    // Konten yang menyeksualisasi anak tidak ditampilkan.
    private val blocked = setOf("lolicon", "shotacon")
    private val blockedTagIds = setOf("19440", "32341")
    private val exclude = " -tag:lolicon -tag:shotacon"

    private fun ext(t: String) = when (t) {
        "p" -> "png"
        "g" -> "gif"
        "w" -> "webp"
        else -> "jpg"
    }

    // ---------- Daftar & pencarian (HTML) ----------

    override fun latest(page: Int) = list(Net.get("$base/?page=$page"))

    override fun search(query: String, page: Int) =
        list(Net.get("$base/search/?q=${enc(query + exclude)}&page=$page"))

    private fun list(doc: Document): List<Comic> {
        var items = doc.select(".index-container:not(.index-popular) div.gallery")
        if (items.isEmpty()) items = doc.select("div.gallery")
        return items.mapNotNull { g ->
            val tagIds = g.attr("data-tags").split(" ").toSet()
            if (tagIds.any { it in blockedTagIds }) return@mapNotNull null
            val a = g.selectFirst("a[href]") ?: return@mapNotNull null
            val title = g.selectFirst(".caption")?.text() ?: g.text()
            Comic(title.trim(), a.absUrl("href"), g.selectFirst("img")?.img().orEmpty())
        }.distinctBy { it.url }
    }

    // ---------- Data galeri ----------

    private fun galleryId(url: String) =
        Regex("""/g/(\d+)""").find(url)?.groupValues?.get(1) ?: throw IOException("Link galeri tidak valid.")

    /** JSON galeri dari skrip `JSON.parse("...")` di halaman, atau dari API lama. Null jika tidak ada. */
    private fun galleryJson(doc: Document, id: String): JSONObject? {
        for (sc in doc.select("script")) {
            val m = Regex("""JSON\.parse\(\s*"(.*?)"\s*\)""", RegexOption.DOT_MATCHES_ALL).find(sc.data())
                ?: continue
            try {
                // Bongkar escape string JavaScript lewat parser JSON.
                val raw = JSONArray("[\"" + m.groupValues[1] + "\"]").getString(0)
                val o = JSONObject(raw)
                if (o.has("media_id")) return o
            } catch (e: Exception) {
                // coba skrip berikutnya
            }
        }
        return try {
            JSONObject(Net.getText("$base/api/gallery/$id"))
        } catch (e: CloudflareException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonBlocked(g: JSONObject): Boolean {
        val tags = g.optJSONArray("tags") ?: return false
        return (0 until tags.length()).any { tags.getJSONObject(it).optString("name") in blocked }
    }

    private fun htmlBlocked(doc: Document) =
        doc.select("#tags a.tag .name").any { it.text().trim() in blocked }

    private fun load(url: String): Triple<Document, String, JSONObject?> {
        val id = galleryId(url)
        val doc = Net.get("$base/g/$id/")
        val json = galleryJson(doc, id)
        if ((json != null && jsonBlocked(json)) || htmlBlocked(doc)) throw IOException("Konten ini tidak ditampilkan.")
        return Triple(doc, id, json)
    }

    override fun detail(url: String): Detail {
        val (doc, _, g) = load(url)
        val labels = mapOf(
            "parody" to "Parodi", "character" to "Karakter", "tag" to "Tag", "artist" to "Artis",
            "group" to "Grup", "language" to "Bahasa", "category" to "Kategori"
        )
        val byType = linkedMapOf<String, MutableList<String>>()
        var title = doc.selectFirst("#info h1")?.text().orEmpty()
        var cover = doc.selectFirst("#cover img")?.img().orEmpty()
        var n = doc.select("#thumbnail-container .thumb-container").size
        if (g != null) {
            val t = g.optJSONObject("title")
            if (t != null) title = t.optString("pretty").ifBlank { t.optString("english") }.ifBlank { title }
            val media = g.optString("media_id")
            val coverT = g.optJSONObject("images")?.optJSONObject("cover")?.optString("t").orEmpty()
            if (cover.isEmpty()) cover = "https://t.nhentai.net/galleries/$media/cover.${ext(coverT)}"
            n = g.optInt("num_pages", n)
            val tags = g.optJSONArray("tags")
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    val tg = tags.getJSONObject(i)
                    byType.getOrPut(tg.optString("type")) { mutableListOf() }.add(tg.optString("name"))
                }
            }
        } else {
            for (box in doc.select("#tags .tag-container")) {
                val names = box.select("a.tag .name").map { it.text() }
                if (names.isEmpty()) continue
                val label = box.ownText().trim().trimEnd(':').lowercase().removeSuffix("s")
                byType.getOrPut(label) { mutableListOf() }.addAll(names)
            }
        }
        val info = byType.entries.joinToString("\n") { (k, v) -> "${labels[k] ?: k}: ${v.joinToString(", ")}" }
        return Detail(title, cover, "$n halaman\n$info", listOf(Chapter("Baca ($n halaman)", url, "")))
    }

    override fun pages(chapterUrl: String): List<String> {
        val (doc, _, g) = load(chapterUrl)
        val pages = g?.optJSONObject("images")?.optJSONArray("pages")
        if (g != null && pages != null && pages.length() > 0) {
            val media = g.optString("media_id")
            return (0 until pages.length()).map {
                "https://i.nhentai.net/galleries/$media/${it + 1}.${ext(pages.getJSONObject(it).optString("t"))}"
            }
        }
        // Cadangan: thumbnail ".../galleries/<media>/3t.jpg" -> gambar penuh ".../galleries/<media>/3.jpg"
        return doc.select("#thumbnail-container img").map { it.img() }
            .filter { it.contains("/galleries/") }
            .map { u ->
                u.replace(Regex("""//t(\d*)\."""), "//i$1.")
                    .replace(Regex("""/(\d+)t\.(\w+)$"""), "/$1.$2")
            }
            .distinct()
    }
}

object Sources {
    fun current(): Source? {
        val b = Prefs.baseUrl
        if (b.isEmpty()) return null
        return when (Prefs.theme) {
            "madara" -> Madara(b)
            "nhentai" -> Nhentai(b)
            else -> MangaThemesia(b)
        }
    }

    /** Cek situs, kembalikan (tema, jumlah komik terbaca). */
    fun detect(base: String): Pair<String, Int> {
        if (base.contains("nhentai", ignoreCase = true)) {
            val n = Nhentai(base).latest(1).size
            if (n > 0) return "nhentai" to n
            throw IOException("Situs terbuka tapi daftar galeri kosong.")
        }
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
