package com.yudha.komik

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Comic(val title: String, val url: String, val cover: String)
data class Chapter(val name: String, val url: String, val date: String)
data class Tag(val name: String, val count: String, val query: String)
data class TagGroup(val label: String, val tags: List<Tag>)

data class Detail(
    val title: String,
    val cover: String,
    val synopsis: String,
    val chapters: List<Chapter>,
    /** Status bookmark di akun situs: null = belum login / tidak didukung. */
    val siteFav: Boolean? = null,
    val subtitle: String = "",
    val code: String = "",
    val tagGroups: List<TagGroup> = emptyList(),
    /** Thumbnail tiap halaman (untuk grid seperti di web). Kosong = tampilkan daftar chapter. */
    val thumbs: List<String> = emptyList(),
    val pageCount: Int = 0,
    val uploaded: String = ""
)

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

    /** Bookmark/favorit di akun situs (butuh login). */
    fun siteFavorites(page: Int): List<Comic> =
        throw IOException("Sumber ini belum mendukung bookmark situs.")

    /** Halaman login situs. */
    fun loginUrl(): String? = null

    /** Tambah/hapus bookmark di akun situs. Mengembalikan status terbaru. */
    fun setSiteFavorite(url: String, add: Boolean): Boolean =
        throw IOException("Sumber ini belum mendukung bookmark situs.")
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
        } catch (e: Exception) {
            null // API diblokir/404: lanjut pakai data dari halaman
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

    private val order = listOf(
        "parody" to "Parodies", "character" to "Characters", "tag" to "Tags", "artist" to "Artists",
        "group" to "Groups", "language" to "Languages", "category" to "Categories"
    )
    private val typeOfLabel = mapOf(
        "parodies" to "parody", "characters" to "character", "tags" to "tag", "artists" to "artist",
        "groups" to "group", "languages" to "language", "categories" to "category"
    )

    private fun fmtCount(n: Int) = when {
        n >= 1000 -> "${n / 1000}K"
        n > 0 -> n.toString()
        else -> ""
    }

    override fun detail(url: String): Detail {
        val (doc, id, g) = load(url)
        val byType = linkedMapOf<String, List<Tag>>()
        var title = doc.selectFirst("#info h1, h1.title")?.text().orEmpty()
        var subtitle = doc.selectFirst("#info h2, h2.title")?.text().orEmpty()
        var cover = doc.selectFirst("#cover img")?.img().orEmpty()
        var thumbList = thumbs(doc)
        var n = thumbList.size
        var uploaded = doc.selectFirst("#tags time")?.text().orEmpty()
        if (g != null) {
            val t = g.optJSONObject("title")
            if (t != null) {
                title = t.optString("english").ifBlank { t.optString("pretty") }.ifBlank { title }
                subtitle = t.optString("japanese").ifBlank { subtitle }
            }
            val media = g.optString("media_id")
            val coverT = g.optJSONObject("images")?.optJSONObject("cover")?.optString("t").orEmpty()
            if (cover.isEmpty()) cover = "https://t.nhentai.net/galleries/$media/cover.${ext(coverT)}"
            n = g.optInt("num_pages", n)
            val up = g.optLong("upload_date", 0L)
            if (up > 0) uploaded = SimpleDateFormat("d MMMM yyyy", Locale("id")).format(Date(up * 1000))
            val tags = g.optJSONArray("tags")
            if (tags != null) {
                val tmp = linkedMapOf<String, MutableList<Tag>>()
                for (i in 0 until tags.length()) {
                    val tg = tags.getJSONObject(i)
                    val type = tg.optString("type")
                    val name = tg.optString("name")
                    tmp.getOrPut(type) { mutableListOf() }.add(Tag(name, fmtCount(tg.optInt("count")), "$type:\"$name\""))
                }
                byType.putAll(tmp)
            }
            if (thumbList.isEmpty()) {
                val pages = g.optJSONObject("images")?.optJSONArray("pages")
                if (pages != null) {
                    thumbList = (0 until pages.length()).map {
                        "https://t.nhentai.net/galleries/$media/${it + 1}t.${ext(pages.getJSONObject(it).optString("t"))}"
                    }
                }
            }
        } else {
            for (box in doc.select("#tags .tag-container")) {
                val type = typeOfLabel[box.ownText().trim().trimEnd(':').lowercase()] ?: continue
                val tags = box.select("a.tag").map { a ->
                    val name = a.selectFirst(".name")?.text() ?: a.ownText()
                    Tag(name, a.selectFirst(".count")?.text().orEmpty(), "$type:\"$name\"")
                }
                if (tags.isNotEmpty()) byType[type] = tags
            }
        }
        val groups = order.mapNotNull { (k, label) -> byType[k]?.takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } }
        // Tombol favorit hanya ada di halaman kalau sudah login.
        val siteFav = doc.selectFirst("#favorite")?.text()?.contains("Unfavorite", ignoreCase = true)
        return Detail(
            title, cover, "", listOf(Chapter("Baca", url, "")), siteFav,
            subtitle = subtitle, code = "#$id", tagGroups = groups, thumbs = thumbList,
            pageCount = n, uploaded = uploaded
        )
    }

    private fun thumbs(doc: Document) =
        doc.select("#thumbnail-container img, .thumbs img, a.gallerythumb img").map { it.img() }
            .filter { it.contains("/galleries/") }.distinct()

    override fun loginUrl() = "$base/login/"

    override fun setSiteFavorite(url: String, add: Boolean): Boolean {
        val id = galleryId(url)
        val page = "$base/g/$id/"
        // Token CSRF: dari halaman galeri, cadangan dari cookie "csrftoken".
        val html = Net.get(page).html()
        var token = Regex("""csrf_token\W+([A-Za-z0-9]{20,})""").findAll(html).lastOrNull()?.groupValues?.get(1)
        if (token == null) {
            token = CookieManager.getInstance().getCookie(base)?.split(";")
                ?.map { it.trim() }?.firstOrNull { it.startsWith("csrftoken=") }?.substringAfter("=")
        }
        val tok: String = token ?: ""
        if (tok.isEmpty()) throw IOException("Belum login. Buka menu ⋮ → Login ke situs.")
        val resp = Net.postText(
            "$base/api/gallery/$id/${if (add) "" else "un"}favorite",
            mapOf("Referer" to page, "X-CSRFToken" to tok, "X-Requested-With" to "XMLHttpRequest")
        )
        return try {
            JSONObject(resp).optBoolean("favorited", add)
        } catch (e: Exception) {
            resp.contains("true")
        }
    }

    override fun siteFavorites(page: Int): List<Comic> {
        val doc = Net.get("$base/favorites/?page=$page")
        if (doc.location().contains("/login")) {
            throw IOException("Belum login. Buka menu ⋮ → Login ke situs.")
        }
        return list(doc)
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
        return thumbs(doc)
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
