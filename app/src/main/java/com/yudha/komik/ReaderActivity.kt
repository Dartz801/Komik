package com.yudha.komik

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pembaca dengan dua mode:
 * - "gulir": halaman bersambung ke bawah
 * - "geser": satu halaman satu layar, digeser ke samping
 * Ketuk layar untuk menampilkan/menyembunyikan bar (nomor halaman + ganti mode).
 */
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
    private lateinit var modeBtn: Button
    private lateinit var transBtn: Button
    private val snap = PagerSnapHelper()
    private val pages = PageAdapter {
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateInfo()
    }

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
        modeBtn = findViewById(R.id.mode)
        transBtn = findViewById(R.id.trans)
        source = src
        startPage = intent.getIntExtra("page", 0)

        // Tombol pindah bab hanya untuk komik yang punya lebih dari satu bab.
        val multi = Session.chapters.size > 1
        prev.visibility = if (multi) View.VISIBLE else View.GONE
        next.visibility = if (multi) View.VISIBLE else View.GONE
        // Daftar chapter dari situs urut terbaru -> terlama.
        prev.setOnClickListener { open(src, index + 1) }
        next.setOnClickListener { open(src, index - 1) }

        modeBtn.setOnClickListener {
            Prefs.readMode = if (Prefs.readMode == "geser") "gulir" else "geser"
            applyMode()
        }
        // Terjemahan otomatis (saklar)
        pages.scope = lifecycleScope
        pages.lang = TranslateEngine.langFromTag(Session.lang)
        pages.translate = Prefs.translate
        updateTransBtn()
        TranslateEngine.onStatus = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        transBtn.setOnClickListener {
            Prefs.translate = !Prefs.translate
            pages.translate = Prefs.translate
            updateTransBtn()
            pages.notifyDataSetChanged()
            if (Prefs.translate) {
                Toast.makeText(this, "Terjemahan ${TranslateEngine.langName(pages.lang)} → Indonesia nyala", Toast.LENGTH_SHORT).show()
            }
        }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = updateInfo()
        })

        applyMode()
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

    private fun updateTransBtn() {
        transBtn.text = if (Prefs.translate) "Terjemahan: Nyala" else "Terjemahan: Mati"
    }

    override fun onDestroy() {
        super.onDestroy()
        TranslateEngine.onStatus = null
    }

    private fun currentPos(): Int =
        (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0

    /** Terapkan mode baca yang tersimpan, tetap di halaman yang sama. */
    private fun applyMode() {
        val pos = if (rv.layoutManager == null) 0 else currentPos()
        val horizontal = Prefs.readMode == "geser"
        pages.horizontal = horizontal
        rv.layoutManager = LinearLayoutManager(
            this, if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL, false
        )
        snap.attachToRecyclerView(if (horizontal) rv else null)
        rv.adapter = pages
        rv.scrollToPosition(pos)
        modeBtn.text = if (horizontal) "Mode: Geser ↔" else "Mode: Gulir ↕"
        updateInfo()
    }

    private fun updateInfo() {
        if (!::chTitle.isInitialized) return
        val total = pages.itemCount
        val page = if (total == 0) "" else "${(currentPos() + 1).coerceAtMost(total)} / $total"
        val chs = Session.chapters
        chTitle.text = if (chs.size > 1) "${chs.getOrNull(index)?.name.orEmpty()}  ·  $page" else page
    }

    private fun open(src: Source, i: Int) {
        val chs = Session.chapters
        if (i !in chs.indices) return
        index = i
        val ch = chs[i]
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
                rv.post { updateInfo() }
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

    /** true = mode geser (satu halaman satu layar), false = mode gulir. */
    var horizontal = false

    /** Terjemahan otomatis nyala? */
    var translate = false

    /** Bahasa asal (kode ML Kit). */
    var lang = "en"
    var scope: CoroutineScope? = null

    class VH(root: FrameLayout, val img: ImageView, val overlay: TranslateOverlay) : RecyclerView.ViewHolder(root) {
        var job: Job? = null
    }

    override fun getItemViewType(position: Int) = if (horizontal) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val full = viewType == 1
        val root = FrameLayout(ctx)
        root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val iv = ImageView(ctx)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.adjustViewBounds = !full
        root.addView(
            iv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val overlay = TranslateOverlay(ctx)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        iv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> overlay.invalidate() }
        return VH(root, iv, overlay)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) = bind(h, items[i])

    override fun onViewRecycled(h: VH) {
        h.job?.cancel()
        h.overlay.clear()
    }

    private fun bind(h: VH, url: String) {
        val iv = h.img
        val full = h.itemViewType == 1
        h.job?.cancel()
        h.overlay.clear()
        iv.minimumHeight = if (full) 0 else (iv.resources.displayMetrics.density * 400).toInt()
        iv.setOnClickListener { onTap() }
        val tr = translate
        iv.load(url) {
            error(R.drawable.ic_retry)
            if (tr) allowHardware(false) // bitmap perlu bisa dibaca untuk mengenali teks
            listener(
                onSuccess = { _, res ->
                    iv.minimumHeight = 0
                    if (tr) startTranslate(h, url, res.drawable)
                },
                onError = { _, _ -> iv.setOnClickListener { bind(h, url) } } // ketuk untuk coba lagi
            )
        }
    }

    private fun startTranslate(h: VH, url: String, d: Drawable) {
        val bmp = (d as? BitmapDrawable)?.bitmap ?: return
        TranslateEngine.cached(url)?.let {
            h.overlay.show(h.img, it)
            return
        }
        h.job = scope?.launch {
            delay(350) // halaman yang cuma lewat sekilas saat menggulir tidak diproses
            try {
                val r = TranslateEngine.process(url, bmp, lang)
                h.overlay.show(h.img, r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslateEngine.onStatus?.invoke("Terjemahan gagal: ${e.message}")
            }
        }
    }

    fun set(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
