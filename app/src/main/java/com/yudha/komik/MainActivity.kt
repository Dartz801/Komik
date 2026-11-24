package com.yudha.komik

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: ComicAdapter
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var tabs: TabLayout
    private var tab = 0 // 0 = Terbaru, 1 = Favorit (di HP), 2 = Bookmark situs (akun)
    private var query: String? = null
    private var page = 1
    private var loading = false
    private var hasMore = true
    private var loadedBase = ""
    private var askedSettings = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        tabs = findViewById(R.id.tabs)
        intent.getStringExtra("query")?.let {
            query = it
            title = it
        }

        adapter = ComicAdapter { c ->
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra("url", c.url).putExtra("title", c.title).putExtra("cover", c.cover)
            )
        }
        val rv = findViewById<RecyclerView>(R.id.list)
        val glm = GridLayoutManager(this, 3)
        rv.layoutManager = glm
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (tab != 1 && dy > 0 && !loading && hasMore &&
                    glm.findLastVisibleItemPosition() >= adapter.itemCount - 6
                ) load(false)
            }
        })
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                load(true)
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.baseUrl.isEmpty()) {
            showMessage("Belum ada link situs komik.\nBuka menu ⋮ → Pengaturan untuk mengisinya.")
            if (!askedSettings) {
                askedSettings = true
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        if (loadedBase != Prefs.baseUrl || tab != 0) {
            loadedBase = Prefs.baseUrl
            load(true)
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun load(reset: Boolean) {
        if (reset) {
            job?.cancel()
            page = 1
            hasMore = true
            adapter.set(emptyList())
        }
        message.visibility = View.GONE
        if (tab == 1) {
            val fav = Prefs.favorites()
            adapter.set(fav)
            if (fav.isEmpty()) showMessage("Belum ada favorit.\nBuka komik lalu tekan ☆ Favorit.")
            return
        }
        val src = Sources.current() ?: return
        loading = true
        progress.visibility = View.VISIBLE
        val q = query
        val p = page
        job = lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    when {
                        tab == 2 -> src.siteFavorites(p)
                        q.isNullOrBlank() -> src.latest(p)
                        else -> src.search(q, p)
                    }
                }
                if (list.isEmpty()) hasMore = false
                if (p == 1) adapter.set(list) else adapter.add(list)
                page = p + 1
                if (adapter.itemCount == 0) {
                    showMessage(
                        when {
                            tab == 2 -> "Bookmark di akun situs masih kosong."
                            q.isNullOrBlank() -> "Tidak ada komik ditemukan."
                            else -> "Tidak ada hasil untuk \"$q\"."
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hasMore = false
                if (p == 1) showMessage(e.message ?: e.toString())
            } finally {
                loading = false
                progress.visibility = View.GONE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val item = menu.findItem(R.id.search)
        val sv = item.actionView as SearchView
        sv.queryHint = "Cari komik…"
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String): Boolean {
                query = q
                sv.clearFocus()
                if (tab != 0) tabs.getTabAt(0)?.select() else load(true)
                return true
            }
            override fun onQueryTextChange(t: String) = false
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(i: MenuItem) = true
            override fun onMenuItemActionCollapse(i: MenuItem): Boolean {
                if (query != null) {
                    query = null
                    if (tab == 0) load(true)
                }
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        if (item.itemId == R.id.login) {
            val url = Sources.current()?.loginUrl()
            if (url == null) {
                showMessage("Sumber ini belum mendukung login.")
            } else {
                startActivity(
                    Intent(this, WebViewActivity::class.java)
                        .putExtra("url", url)
                        .putExtra("title", "Login ke situs")
                        .putExtra("hint", "Login seperti biasa di situsnya. Setelah berhasil masuk, tekan Selesai.")
                )
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class ComicAdapter(private val onClick: (Comic) -> Unit) : RecyclerView.Adapter<ComicAdapter.VH>() {
    private val items = mutableListOf<Comic>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_comic, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val c = items[i]
        h.title.text = c.title
        h.cover.load(c.cover) {
            placeholder(R.color.placeholder)
            error(R.color.placeholder)
        }
        h.itemView.setOnClickListener { onClick(c) }
    }

    fun set(list: List<Comic>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(list: List<Comic>) {
        val fresh = list.filter { n -> items.none { it.url == n.url } }
        val start = items.size
        items.addAll(fresh)
        notifyItemRangeInserted(start, fresh.size)
    }
}
