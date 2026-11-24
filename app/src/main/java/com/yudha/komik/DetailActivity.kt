package com.yudha.komik

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {
    private lateinit var comic: Comic
    private var detail: Detail? = null
    private val adapter = DetailAdapter()

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
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        load()
    }

    private var needReload = false

    private fun src(): Source = Sources.current() ?: throw IllegalStateException("Link situs belum diisi.")

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
                if (detail?.chapters.isNullOrEmpty()) {
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
        adapter.notifyDataSetChanged() // perbarui tanda "terakhir dibaca"
        if (needReload) {
            needReload = false
            load()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun openChapter(index: Int) {
        val d = detail ?: return
        Session.comicUrl = comic.url
        Session.chapters = d.chapters
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("index", index))
    }

    private inner class DetailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getItemCount() = 1 + (detail?.chapters?.size ?: 0)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            val layout = if (viewType == 0) R.layout.item_detail_header else R.layout.item_chapter
            return object : RecyclerView.ViewHolder(inf.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val v = h.itemView
            val d = detail
            val last = Prefs.lastRead(comic.url)
            if (position == 0) {
                val cover = d?.cover?.ifBlank { null } ?: comic.cover
                v.findViewById<ImageView>(R.id.cover).load(cover) { placeholder(R.color.placeholder) }
                v.findViewById<TextView>(R.id.title).text = d?.title?.ifBlank { null } ?: comic.title
                v.findViewById<TextView>(R.id.synopsis).text = d?.synopsis.orEmpty()

                val fav = v.findViewById<Button>(R.id.fav)
                val c = comic.copy(title = d?.title?.ifBlank { null } ?: comic.title, cover = cover)
                val synced = d?.siteFav != null // sudah login: favorit ikut akun situs
                val star = { on: Boolean -> (if (on) "★ Favorit" else "☆ Favorit") + if (synced) " (akun)" else "" }
                fav.text = star(Prefs.isFav(comic.url))
                fav.setOnClickListener {
                    if (!synced) {
                        fav.text = star(Prefs.toggleFav(c))
                        return@setOnClickListener
                    }
                    val want = !Prefs.isFav(comic.url)
                    fav.isEnabled = false
                    fav.text = "Menyimpan ke akun…"
                    lifecycleScope.launch {
                        try {
                            val now = withContext(Dispatchers.IO) { src().setSiteFavorite(comic.url, want) }
                            Prefs.setFav(c, now)
                            fav.text = star(now)
                        } catch (e: Exception) {
                            fav.text = star(Prefs.isFav(comic.url))
                            Toast.makeText(this@DetailActivity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                        } finally {
                            fav.isEnabled = true
                        }
                    }
                }

                val chapters = d?.chapters.orEmpty()
                val cont = v.findViewById<Button>(R.id.cont)
                val lastIdx = chapters.indexOfFirst { it.url == last }
                cont.visibility = if (lastIdx >= 0) View.VISIBLE else View.GONE
                if (lastIdx >= 0) {
                    cont.text = "Lanjutkan: ${chapters[lastIdx].name}"
                    cont.setOnClickListener { openChapter(lastIdx) }
                }
                val start = v.findViewById<Button>(R.id.start)
                start.visibility = if (chapters.isNotEmpty()) View.VISIBLE else View.GONE
                start.setOnClickListener { openChapter(chapters.lastIndex) }
            } else {
                val ch = d?.chapters?.getOrNull(position - 1) ?: return
                val name = v.findViewById<TextView>(R.id.name)
                name.text = if (ch.url == last) "▶ ${ch.name}" else ch.name
                name.setTypeface(null, if (ch.url == last) Typeface.BOLD else Typeface.NORMAL)
                v.findViewById<TextView>(R.id.date).text = ch.date
                v.setOnClickListener { openChapter(position - 1) }
            }
        }
    }
}
