package com.yudha.komik

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
    /** Tag bahasa galeri (english/japanese/chinese) untuk terjemahan. */
    var lang = "english"
    /** Komik yang sedang dibaca (untuk riwayat). */
    var comic: Comic? = null
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
 * nhentai lewat API resmi v2 (https://nhentai.net/api/v2/...).
 * - Server gambar diambil dari /api/v2/cdn (tidak menebak-nebak lagi).
 * - API key (Pengaturan) dipakai otomatis: batas permintaan lebih longgar + bookmark akun.
 * - Ada jeda antar permintaan supaya tidak terkena pembatasan situs.
 * Satu galeri = satu "chapter".
 */
class Nhentai(private val base: String) : Source {
    // Konten yang menyeksualisasi anak tidak ditampilkan.
    private val blocked = setOf("lolicon", "shotacon")
    private val blockedTagIds = setOf(19440, 32341)
    private val exclude = " -tag:lolicon -tag:shotacon"
    private val api = "$base/api/v2"

    private val order = listOf(
        "parody" to "Parodies", "character" to "Characters", "tag" to "Tags", "artist" to "Artists",
        "group" to "Groups", "language" to "Languages", "category" to "Categories"
    )

    companion object {
        @Volatile private var imageServers: List<String> = emptyList()
        @Volatile private var thumbServers: List<String> = emptyList()
        private val next = HashMap<String, Long>()
        private val galleryCache = object : LinkedHashMap<String, JSONObject>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 20
        }

        /** Jeda minimal antar permintaan per jenis, mengikuti batas resmi situs. */
        private fun throttle(kind: String, ms: Long) {
            val sleep: Long
            synchronized(next) {
                val now = System.currentTimeMillis()
                val at = maxOf(now, next[kind] ?: 0L)
                next[kind] = at + ms
                sleep = at - now
            }
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    private val hasKey get() = Prefs.apiKey.isNotBlank()

    private fun get(kind: String, msWithKey: Long, msAnon: Long, url: String): JSONObject {
        throttle(kind, if (hasKey) msWithKey else msAnon)
        return JSONObject(Net.api("GET", url))
    }

    private fun cdn() {
        if (imageServers.isNotEmpty() && thumbServers.isNotEmpty()) return
        val o = get("cdn", 2000, 2000, "$api/cdn")
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it).trimEnd('/') } }.orEmpty()
        imageServers = arr("image_servers").ifEmpty { listOf("https://i.nhentai.net") }
        thumbServers = arr("thumb_servers").ifEmpty { listOf("https://t.nhentai.net") }
    }

    private fun join(servers: List<String>, i: Int, path: String): String {
        val p = path.trimStart('/')
        if (p.isEmpty()) return ""
        if (p.startsWith("http")) return p
        return "${servers[i % servers.size]}/$p"
    }

    private fun ext(t: String) = when (t) {
        "p" -> "png"
        "g" -> "gif"
        "w" -> "webp"
        else -> "jpg"
    }

    private fun fmtCount(n: Int) = when {
        n >= 1000 -> "${n / 1000}K"
        n > 0 -> n.toString()
        else -> ""
    }

    // ---------- Daftar, pencarian, bookmark akun ----------

    private fun results(o: JSONObject): List<Comic> {
        cdn()
        val arr = o.optJSONArray("result") ?: o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Comic>()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            val ids = g.optJSONArray("tag_ids")
            if (ids != null && (0 until ids.length()).any { ids.optInt(it) in blockedTagIds }) continue
            val id = g.opt("id")?.toString() ?: continue
            val title = g.optString("english_title").ifBlank { g.optString("japanese_title") }
            out.add(Comic(title, "$base/g/$id/", join(thumbServers, 0, g.optString("thumbnail"))))
        }
        return out
    }

    override fun latest(page: Int) =
        results(get("galleries", 2000, 4000, "$api/galleries?page=$page&per_page=25&sort=date"))

    override fun search(query: String, page: Int) =
        results(get("search", 3000, 6000, "$api/search?query=${enc(query + exclude)}&sort=date&page=$page"))

    override fun siteFavorites(page: Int): List<Comic> {
        if (!hasKey) throw IOException("Isi API key dulu di menu ⋮ → Pengaturan.")
        return results(get("favorites", 4000, 4000, "$api/favorites?page=$page"))
    }

    override fun setSiteFavorite(url: String, add: Boolean): Boolean {
        if (!hasKey) throw IOException("Isi API key dulu di menu ⋮ → Pengaturan.")
        val id = galleryId(url)
        throttle("favorite", 4000)
        Net.api(if (add) "POST" else "DELETE", "$api/galleries/$id/favorite")
        synchronized(galleryCache) { galleryCache.remove(id) }
        return add
    }

    // ---------- Galeri ----------

    private fun galleryId(url: String) =
        Regex("""/g/(\d+)""").find(url)?.groupValues?.get(1) ?: throw IOException("Link galeri tidak valid.")

    /** Data galeri (disimpan sebentar supaya detail -> baca tidak memanggil API dua kali). */
    private fun gallery(url: String): JSONObject {
        val id = galleryId(url)
        synchronized(galleryCache) { galleryCache[id] }?.let { return it }
        val inc = if (hasKey) "?include=favorite" else ""
        val g = get("gallery", 1400, 3000, "$api/galleries/$id$inc")
        val tags = g.optJSONArray("tags")
        if (tags != null && (0 until tags.length()).any { tags.getJSONObject(it).optString("name") in blocked }) {
            throw IOException("Konten ini tidak ditampilkan.")
        }
        synchronized(galleryCache) { galleryCache[id] = g }
        return g
    }

    private fun pathOf(o: JSONObject?, key: String): String {
        val v = o?.opt(key) ?: return ""
        return if (v is JSONObject) v.optString("path") else v.toString()
    }

    override fun detail(url: String): Detail {
        val g = gallery(url)
        cdn()
        val t = g.optJSONObject("title")
        val title = t?.optString("english")?.ifBlank { null } ?: t?.optString("pretty").orEmpty()
        val subtitle = t?.optString("japanese").orEmpty()
        val byType = linkedMapOf<String, MutableList<Tag>>()
        val tags = g.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val tg = tags.getJSONObject(i)
                val type = tg.optString("type")
                val name = tg.optString("name")
                byType.getOrPut(type) { mutableListOf() }
                    .add(Tag(name, fmtCount(tg.optInt("count")), "$type:\"$name\""))
            }
        }
        val groups = order.mapNotNull { (k, label) -> byType[k]?.takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } }
        val pages = g.optJSONArray("pages")
        val thumbs = if (pages == null) emptyList() else (0 until pages.length()).map {
            join(thumbServers, 0, pathOf(pages.getJSONObject(it), "thumbnail"))
        }.filter { it.isNotEmpty() }
        val up = g.optLong("upload_date", 0L)
        val uploaded = if (up > 0) SimpleDateFormat("d MMMM yyyy", Locale("id")).format(Date(up * 1000)) else ""
        val siteFav = if (hasKey && g.has("is_favorited") && !g.isNull("is_favorited")) g.optBoolean("is_favorited") else null
        val cover = join(thumbServers, 0, pathOf(g, "cover")).ifEmpty { join(thumbServers, 0, pathOf(g, "thumbnail")) }
        return Detail(
            title, cover, "", listOf(Chapter("Baca", url, "")), siteFav,
            subtitle = subtitle, code = "#${g.opt("id")}", tagGroups = groups, thumbs = thumbs,
            pageCount = g.optInt("num_pages", thumbs.size), uploaded = uploaded
        )
    }

    override fun pages(chapterUrl: String): List<String> {
        val g = gallery(chapterUrl)
        cdn()
        val pages = g.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).map { join(imageServers, 0, pathOf(pages.getJSONObject(it), "path")) }
            .filter { it.isNotEmpty() }
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
