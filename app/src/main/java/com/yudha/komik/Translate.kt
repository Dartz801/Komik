package com.yudha.komik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.ImageView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Terjemahan halaman komik di HP (offline setelah model diunduh):
 * 1. baca teks di gambar (ML Kit Text Recognition: Latin / Jepang / China)
 * 2. terjemahkan tiap balon ke Bahasa Indonesia (ML Kit Translate)
 */
object TranslateEngine {
    data class Block(val box: Rect, val text: String)
    data class Result(val blocks: List<Block>, val w: Int, val h: Int)

    /** Pesan status untuk ditampilkan (mis. sedang mengunduh model). */
    @Volatile var onStatus: ((String) -> Unit)? = null

    private val cache = object : LinkedHashMap<String, Result>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 80
    }
    private val translators = HashMap<String, Translator>()
    private val recognizers = HashMap<String, TextRecognizer>()
    private val ready = HashSet<String>()
    private val mutex = Mutex() // satu halaman diproses bergantian, supaya ringan

    /** Kode bahasa ML Kit dari tag bahasa galeri. */
    fun langFromTag(tag: String) = when (tag.lowercase()) {
        "japanese" -> TranslateLanguage.JAPANESE
        "chinese" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    fun langName(code: String) = when (code) {
        TranslateLanguage.JAPANESE -> "Jepang"
        TranslateLanguage.CHINESE -> "China"
        else -> "Inggris"
    }

    fun cached(url: String): Result? = synchronized(cache) { cache[url] }

    private fun translator(lang: String): Translator = synchronized(translators) {
        translators.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(lang)
                    .setTargetLanguage(TranslateLanguage.INDONESIAN)
                    .build()
            )
        }
    }

    private fun recognizer(lang: String): TextRecognizer = synchronized(recognizers) {
        recognizers.getOrPut(lang) {
            when (lang) {
                TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    suspend fun process(url: String, bmp: Bitmap, lang: String): Result = mutex.withLock {
        cached(url)?.let { return@withLock it }
        val tr = translator(lang)
        if (lang !in ready) {
            onStatus?.invoke("Menyiapkan terjemahan ${langName(lang)} → Indonesia (unduh sekali, ±30 MB)…")
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready.add(lang)
        }
        val text = try {
            recognizer(lang).process(InputImage.fromBitmap(bmp, 0)).await()
        } catch (e: Exception) {
            throw Exception("Pembaca teks belum siap (model sedang diunduh Google Play). Coba lagi sebentar.")
        }
        // Bahasa Jepang/China tidak memakai spasi antar kata
        val joiner = if (lang == TranslateLanguage.ENGLISH) " " else ""
        val out = ArrayList<Block>()
        for (b in text.textBlocks) {
            val box = b.boundingBox ?: continue
            val src = b.lines.joinToString(joiner) { it.text }.trim()
            if (src.count { it.isLetter() } < 2) continue // lewati angka/simbol/efek suara pendek
            val t = try {
                tr.translate(src).await()
            } catch (e: Exception) {
                continue
            }
            if (t.isNotBlank()) out.add(Block(box, t))
        }
        val r = Result(out, bmp.width, bmp.height)
        synchronized(cache) { cache[url] = r }
        r
    }
}

/** Lapisan di atas gambar: kotak putih berisi terjemahan yang menutupi teks asli. */
class TranslateOverlay(ctx: Context) : View(ctx) {
    private var result: TranslateEngine.Result? = null
    private var target: ImageView? = null
    private val dens = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    fun show(iv: ImageView, r: TranslateEngine.Result) {
        target = iv
        result = r
        invalidate()
    }

    fun clear() {
        result = null
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val res = result ?: return
        val iv = target ?: return
        val d = iv.drawable ?: return
        if (res.blocks.isEmpty() || res.w <= 0 || res.h <= 0) return
        // koordinat bitmap -> koordinat drawable -> koordinat layar (imageMatrix)
        val sx = d.intrinsicWidth.toFloat() / res.w
        val sy = d.intrinsicHeight.toFloat() / res.h
        val m = iv.imageMatrix
        val dx = (iv.left + iv.paddingLeft - left).toFloat()
        val dy = (iv.top + iv.paddingTop - top).toFloat()
        for (b in res.blocks) {
            val r = RectF(b.box.left * sx, b.box.top * sy, b.box.right * sx, b.box.bottom * sy)
            m.mapRect(r)
            r.offset(dx, dy)
            // Teks vertikal (Jepang) kotaknya sempit: lebarkan supaya teks Indonesia muat
            if (r.width() < r.height() * 0.7f) {
                val nw = r.height() * 0.8f
                val cx = r.centerX()
                r.left = cx - nw / 2
                r.right = cx + nw / 2
            }
            r.inset(-3 * dens, -3 * dens)
            c.drawRoundRect(r, 6 * dens, 6 * dens, bg)
            drawFit(c, b.text, r)
        }
    }

    private fun make(text: String, w: Int, size: Float): StaticLayout {
        tp.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    /** Tulis teks sebesar mungkin yang masih muat di kotak. */
    private fun drawFit(c: Canvas, text: String, r: RectF) {
        val pad = 3 * dens
        val w = (r.width() - 2 * pad).toInt().coerceAtLeast(1)
        val maxH = r.height() - 2 * pad
        var size = 18f * dens
        var layout = make(text, w, size)
        while (layout.height > maxH && size > 7f * dens) {
            size -= dens
            layout = make(text, w, size)
        }
        c.save()
        c.clipRect(r)
        c.translate(r.left + pad, r.top + pad + ((maxH - layout.height) / 2).coerceAtLeast(0f))
        layout.draw(c)
        c.restore()
    }
}
