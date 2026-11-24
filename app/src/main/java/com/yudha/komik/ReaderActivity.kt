package com.yudha.komik

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.load
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pembaca: gambar disusun vertikal (scroll ke bawah), cocok untuk manga/manhwa. */
class ReaderActivity : AppCompatActivity() {
    private var index = 0
    private var job: Job? = null
    private var source: Source? = null
    private var needReload = false
    private var startPage = 0
    private lateinit var rv: RecyclerView
    private lateinit var bar: View
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var chTitle: TextView
    private lateinit var prev: Button
    private lateinit var next: Button
    private val pages = PageAdapter { bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = Sources.current()
        if (Session.chapters.isEmpty() || src == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_reader)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rv = findViewById(R.id.list)
        bar = findViewById(R.id.bar)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        chTitle = findViewById(R.id.chTitle)
        prev = findViewById(R.id.prev)
        next = findViewById(R.id.next)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = pages
        rv.setItemViewCacheSize(4)
        source = src
        startPage = intent.getIntExtra("page", 0)

        // Daftar chapter dari situs urut terbaru -> terlama.
        prev.setOnClickListener { open(src, index + 1) }
        next.setOnClickListener { open(src, index - 1) }
        open(src, intent.getIntExtra("index", 0))
    }

    override fun onResume() {
        super.onResume()
        val src = source
        if (needReload && src != null) {
            needReload = false
            open(src, index)
        }
    }

    private fun open(src: Source, i: Int) {
        val chs = Session.chapters
        if (i !in chs.indices) return
        index = i
        val ch = chs[i]
        chTitle.text = ch.name
        prev.isEnabled = i < chs.lastIndex
        next.isEnabled = i > 0
        Prefs.setLastRead(Session.comicUrl, ch.url)
        pages.set(emptyList())
        message.visibility = View.GONE
        message.setOnClickListener(null)
        progress.visibility = View.VISIBLE
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { src.pages(ch.url) }
                pages.set(list)
                rv.scrollToPosition(startPage.coerceIn(0, maxOf(0, list.size - 1)))
                startPage = 0
                if (list.isEmpty()) {
                    message.text = "Gambar chapter tidak ditemukan."
                    message.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudflareException) {
                message.text = "Situs meminta verifikasi.\n\nKetuk di sini untuk verifikasi, lalu tekan Selesai."
                message.visibility = View.VISIBLE
                message.setOnClickListener {
                    needReload = true
                    startActivity(Intent(this@ReaderActivity, WebViewActivity::class.java).putExtra("url", ch.url))
                }
            } catch (e: Exception) {
                message.text = e.message ?: e.toString()
                message.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }
}

class PageAdapter(private val onTap: () -> Unit) : RecyclerView.Adapter<PageAdapter.VH>() {
    private val items = mutableListOf<String>()
    private val preloaded = HashSet<String>()

    class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false) as ImageView)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        bind(h.img, items[i])
        preload(h.img.context, i + 1)
    }

    /** Unduh beberapa halaman berikutnya lebih dulu (ke cache disk) supaya scroll tidak menunggu. */
    private fun preload(ctx: Context, from: Int) {
        for (j in from until minOf(from + 5, items.size)) {
            val u = items[j]
            if (preloaded.add(u)) {
                ctx.imageLoader.enqueue(
                    ImageRequest.Builder(ctx).data(u).memoryCachePolicy(CachePolicy.DISABLED).build()
                )
            }
        }
    }

    private fun bind(iv: ImageView, url: String) {
        iv.minimumHeight = (iv.resources.displayMetrics.density * 400).toInt()
        iv.setOnClickListener { onTap() }
        iv.load(url) {
            error(R.drawable.ic_retry)
            listener(
                onSuccess = { _, _ -> iv.minimumHeight = 0 },
                onError = { _, _ -> iv.setOnClickListener { bind(iv, url) } } // ketuk untuk coba lagi
            )
        }
    }

    fun set(list: List<String>) {
        preloaded.clear()
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
