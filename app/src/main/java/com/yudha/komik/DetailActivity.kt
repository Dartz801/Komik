package com.yudha.komik

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ImageView dengan rasio halaman komik (tinggi = 1,42 x lebar). */
class AspectImageView(ctx: Context, attrs: AttributeSet?) : AppCompatImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, (measuredWidth * 1.42f).toInt())
    }
}

class DetailActivity : AppCompatActivity() {
    private lateinit var comic: Comic
    private var detail: Detail? = null
    private val adapter = DetailAdapter()
    private var needReload = false

    // Warna mengikuti tampilan web
    private val cText = 0xFFD9D9D9.toInt()
    private val cMuted = 0xFF999999.toInt()
    private val cTag = 0xFF4D4D4D.toInt()
    private val cCount = 0xFF333333.toInt()
    private val cRed = 0xFFED2553.toInt()

    private fun src(): Source = Sources.current() ?: throw IllegalStateException("Link situs belum diisi.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        comic = Comic(
            intent.getStringExtra("title").orEmpty(),
            intent.getStringExtra("url").orEmpty(),
            intent.getStringExtra("cover").orEmpty()
        )
        title = comic.title
        val rv = findViewById<RecyclerView>(R.id.list)
        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == TYPE_THUMB) 1 else 3
        }
        rv.layoutManager = glm
        rv.adapter = adapter
        load()
    }

    private fun load() {
        val src = Sources.current() ?: run { finish(); return }
        val progress = findViewById<ProgressBar>(R.id.progress)
        val message = findViewById<TextView>(R.id.message)
        message.visibility = View.GONE
        message.setOnClickListener(null)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { src.detail(comic.url) }
                detail = d
                if (d.siteFav != null) {
                    Prefs.setFav(comic.copy(title = d.title.ifBlank { comic.title }, cover = d.cover.ifBlank { comic.cover }), d.siteFav)
                }
                adapter.notifyDataSetChanged()
                if (d.chapters.isEmpty()) {
                    message.text = "Chapter tidak ditemukan."
                    message.visibility = View.VISIBLE
                }
            } catch (e: CloudflareException) {
                message.text = "Situs meminta verifikasi.\n\nKetuk di sini untuk verifikasi, lalu tekan Selesai."
                message.visibility = View.VISIBLE
                message.setOnClickListener {
                    needReload = true
                    startActivity(Intent(this@DetailActivity, WebViewActivity::class.java).putExtra("url", comic.url))
                }
            } catch (e: Exception) {
                message.text = e.message ?: e.toString()
                message.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged() // perbarui tanda "terakhir dibaca" & favorit
        if (needReload) {
            needReload = false
            load()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun openChapter(index: Int, page: Int = 0) {
        val d = detail ?: return
        Session.comicUrl = comic.url
        Session.chapters = d.chapters
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("index", index).putExtra("page", page))
    }

    private fun openSearch(q: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("query", q))
    }

    // ---------- Pembuat tampilan tag (mirip web) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int, roundLeft: Boolean, roundRight: Boolean) = GradientDrawable().apply {
        setColor(color)
        val r = dp(4).toFloat()
        val l = if (roundLeft) r else 0f
        val rr = if (roundRight) r else 0f
        cornerRadii = floatArrayOf(l, l, rr, rr, rr, rr, l, l)
    }

    private fun tagChip(t: Tag): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(TextView(this).apply {
            text = t.name
            setTextColor(cText)
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = pill(cTag, true, t.count.isEmpty())
        })
        if (t.count.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = t.count
                setTextColor(cMuted)
                textSize = 12f
                setPadding(dp(6), dp(4), dp(8), dp(4))
                background = pill(cCount, false, true)
            })
        }
        if (t.query.isNotEmpty()) row.setOnClickListener { openSearch(t.query) }
        return row
    }

    private fun tagRow(label: String, items: List<View>): View {
        val g = ChipGroup(this)
        g.chipSpacingHorizontal = dp(5)
        g.chipSpacingVertical = dp(5)
        g.addView(TextView(this).apply {
            text = "$label:"
            setTextColor(cText)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            setPadding(0, dp(4), dp(4), dp(4))
        })
        items.forEach { g.addView(it) }
        g.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        return g
    }

    // ---------- Adapter: header + grid thumbnail (atau daftar chapter untuk situs lain) ----------

    private inner class DetailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val thumbMode get() = !detail?.thumbs.isNullOrEmpty()

        override fun getItemCount(): Int {
            val d = detail ?: return 1
            return 1 + if (thumbMode) d.thumbs.size else d.chapters.size
        }

        override fun getItemViewType(position: Int) = when {
            position == 0 -> TYPE_HEADER
            thumbMode -> TYPE_THUMB
            else -> TYPE_CHAPTER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = when (viewType) {
                TYPE_HEADER -> R.layout.item_detail_header
                TYPE_THUMB -> R.layout.item_thumb
                else -> R.layout.item_chapter
            }
            return object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(layout, parent, false)
            ) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            val d = detail
            when (getItemViewType(position)) {
                TYPE_HEADER -> bindHeader(v, d)
                TYPE_THUMB -> {
                    val url = d?.thumbs?.getOrNull(position - 1) ?: return
                    v.findViewById<ImageView>(R.id.thumb).load(url) { placeholder(R.color.placeholder) }
                    v.findViewById<TextView>(R.id.num).text = position.toString()
                    v.setOnClickListener { openChapter(0, position - 1) }
                }
                else -> {
                    val ch = d?.chapters?.getOrNull(position - 1) ?: return
                    val last = Prefs.lastRead(comic.url)
                    val name = v.findViewById<TextView>(R.id.name)
                    name.text = if (ch.url == last) "▶ ${ch.name}" else ch.name
                    name.setTypeface(null, if (ch.url == last) Typeface.BOLD else Typeface.NORMAL)
                    v.findViewById<TextView>(R.id.date).text = ch.date
                    v.setOnClickListener { openChapter(position - 1) }
                }
            }
        }

        private fun bindHeader(v: View, d: Detail?) {
            val cover = d?.cover?.ifBlank { null } ?: comic.cover
            v.findViewById<ImageView>(R.id.cover).load(cover) { placeholder(R.color.placeholder) }
            v.findViewById<TextView>(R.id.title).text = d?.title?.ifBlank { null } ?: comic.title
            v.findViewById<TextView>(R.id.subtitle).apply {
                text = d?.subtitle.orEmpty()
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.code).apply {
                text = d?.code.orEmpty()
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            // Baris tag: Parodies, Characters, Tags, Artists, Groups, Languages, Categories, Pages, Uploaded
            val box = v.findViewById<LinearLayout>(R.id.tagBox)
            box.removeAllViews()
            d?.tagGroups?.forEach { grp -> box.addView(tagRow(grp.label, grp.tags.map { tagChip(it) })) }
            if (d != null && d.pageCount > 0) box.addView(tagRow("Pages", listOf(tagChip(Tag(d.pageCount.toString(), "", "")))))
            if (d != null && d.uploaded.isNotBlank()) {
                box.addView(tagRow("Uploaded", listOf(TextView(this@DetailActivity).apply {
                    text = d.uploaded
                    setTextColor(cMuted)
                    setPadding(0, dp(4), 0, dp(4))
                })))
            }

            v.findViewById<TextView>(R.id.synopsis).apply {
                text = d?.synopsis.orEmpty()
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            // Tombol Favorite (ikut akun situs bila sudah login)
            val fav = v.findViewById<Button>(R.id.fav)
            val c = comic.copy(title = d?.title?.ifBlank { null } ?: comic.title, cover = cover)
            val synced = d?.siteFav != null
            val label = { on: Boolean -> (if (on) "♥ Favorited" else "♡ Favorite") + if (synced) " (akun)" else "" }
            fav.text = label(Prefs.isFav(comic.url))
            fav.setOnClickListener {
                if (!synced) {
                    fav.text = label(Prefs.toggleFav(c))
                    return@setOnClickListener
                }
                val want = !Prefs.isFav(comic.url)
                fav.isEnabled = false
                fav.text = "Menyimpan…"
                lifecycleScope.launch {
                    try {
                        val now = withContext(Dispatchers.IO) { src().setSiteFavorite(comic.url, want) }
                        Prefs.setFav(c, now)
                        fav.text = label(now)
                    } catch (e: Exception) {
                        fav.text = label(Prefs.isFav(comic.url))
                        Toast.makeText(this@DetailActivity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                    } finally {
                        fav.isEnabled = true
                    }
                }
            }

            // Tombol Baca & Lanjutkan
            val chapters = d?.chapters.orEmpty()
            val read = v.findViewById<Button>(R.id.start)
            read.visibility = if (chapters.isNotEmpty()) View.VISIBLE else View.GONE
            read.setOnClickListener { openChapter(chapters.lastIndex) }
            val cont = v.findViewById<Button>(R.id.cont)
            val last = Prefs.lastRead(comic.url)
            val lastIdx = chapters.indexOfFirst { it.url == last }
            cont.visibility = if (lastIdx >= 0 && chapters.size > 1) View.VISIBLE else View.GONE
            if (lastIdx >= 0) {
                cont.text = "Lanjutkan: ${chapters[lastIdx].name}"
                cont.setOnClickListener { openChapter(lastIdx) }
            }

            v.findViewById<TextView>(R.id.section).apply {
                visibility = if (d == null) View.GONE else View.VISIBLE
                text = if (!d?.thumbs.isNullOrEmpty()) "Halaman (${d?.thumbs?.size})" else "Daftar chapter"
            }
        }
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_CHAPTER = 1
        const val TYPE_THUMB = 2
    }
}
