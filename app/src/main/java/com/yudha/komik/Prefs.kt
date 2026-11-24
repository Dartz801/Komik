package com.yudha.komik

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import org.json.JSONArray
import org.json.JSONObject

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    // Gambar diambil lewat OkHttp yang sama (User-Agent, Referer, cookie).
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Net.client }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(Prefs.cacheMb * 1024L * 1024) // batas cache, diatur di Pengaturan
                    .build()
            }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: App
    }
}

object Prefs {
    private val sp by lazy { App.instance.getSharedPreferences("komik", Context.MODE_PRIVATE) }

    /** Link situs komik — diisi sekali di layar Pengaturan. */
    var baseUrl: String
        get() = sp.getString("base", "") ?: ""
        set(v) = sp.edit().putString("base", v).apply()

    /** Batas cache gambar (MB). Berlaku setelah aplikasi dibuka ulang. */
    var cacheMb: Int
        get() = sp.getInt("cacheMb", 500)
        set(v) = sp.edit().putInt("cacheMb", v).apply()

    var theme: String
        get() = sp.getString("theme", "themesia") ?: "themesia"
        set(v) = sp.edit().putString("theme", v).apply()

    fun favorites(): List<Comic> {
        val arr = JSONArray(sp.getString("fav", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Comic(o.getString("t"), o.getString("u"), o.optString("c"))
        }
    }

    fun isFav(url: String) = favorites().any { it.url == url }

    /** Mengembalikan true jika sekarang menjadi favorit. */
    fun toggleFav(c: Comic): Boolean {
        val list = favorites().toMutableList()
        val removed = list.removeAll { it.url == c.url }
        if (!removed) list.add(0, c)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("c", it.cover)) }
        sp.edit().putString("fav", arr.toString()).apply()
        return !removed
    }

    /** Pasang status favorit di HP secara langsung (dipakai saat sinkron dengan akun situs). */
    fun setFav(c: Comic, on: Boolean) {
        if (isFav(c.url) != on) toggleFav(c)
    }

    fun lastRead(comicUrl: String): String? = sp.getString("last:$comicUrl", null)

    fun setLastRead(comicUrl: String, chapterUrl: String) =
        sp.edit().putString("last:$comicUrl", chapterUrl).apply()
}
