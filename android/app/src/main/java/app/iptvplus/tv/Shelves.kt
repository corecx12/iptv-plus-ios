package app.iptvplus.tv

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject

/** Kachel-Formen wie bei Apple TV */
enum class CardStyle { POSTER, WIDE, CHANNEL }

/** Eine Kachel in einer Reihe */
data class Card(
    val title: String,
    val sub: String = "",
    val image: String? = null,
    val progress: Int = -1,          // 0..1000, -1 = keine Leiste
    val payload: Any? = null,
    val more: Boolean = false        // „Alle anzeigen“
)

/** Eine waagerechte Reihe mit Überschrift */
data class Shelf(val title: String, val style: CardStyle, val cards: List<Card>, val source: Any? = null, val placeholder: Boolean = false)

/** graue Platzhalter, solange eine Reihe lädt */
fun placeholderCards(n: Int = 7) = List(n) { Card("") }

/** Beschreibung, Bilder & Infos zu Film oder Serie aus get_vod_info / get_series_info */
data class Meta(val plot: String, val genre: String, val duration: String, val year: String, val rating: String, val backdrop: String, val poster: String) {
    fun line(): String = listOf(
        year, genre.split(",", "/").take(2).joinToString(", ") { it.trim() }.trim(),
        duration, if (rating.isEmpty() || rating == "0" || rating == "0.0") "" else "★ ${rating.take(3).replace('.', ',')}"
    ).filter { it.isNotBlank() }.joinToString("  ·  ")

    companion object {
        fun from(o: JSONObject): Meta {
            val i = o.optJSONObject("info") ?: JSONObject()
            val bd = i.optJSONArray("backdrop_path")?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.startsWith("http") } }
                ?: i.s("backdrop_path").takeIf { it.startsWith("http") } ?: ""
            val poster = i.s("movie_image").ifEmpty { i.s("cover_big") }.ifEmpty { i.s("cover") }
            val dur = i.s("duration").let { d ->
                // "01:52:00" -> "1 Std. 52 Min."
                val p = d.split(":").mapNotNull { it.toIntOrNull() }
                if (p.size == 3 && (p[0] > 0 || p[1] > 0)) (if (p[0] > 0) "${p[0]} Std. " else "") + "${p[1]} Min." else
                    i.s("episode_run_time").toIntOrNull()?.takeIf { it > 0 }?.let { "$it Min." } ?: ""
            }
            return Meta(
                i.s("plot").ifEmpty { i.s("description") },
                i.s("genre"), dur,
                i.s("releasedate").ifEmpty { i.s("releaseDate") }.take(4),
                i.s("rating"), bd.ifEmpty { poster }, poster
            )
        }
    }
}

private fun dp(c: Context, x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), c.resources.displayMetrics).toInt()

/** Lichtschimmer, der beim Fokussieren einmal über die Kachel läuft (wie tvOS) */
private fun shine(sheen: View, width: Int) {
    sheen.animate().cancel()
    sheen.visibility = View.VISIBLE
    sheen.translationX = -width * 0.7f
    sheen.alpha = 1f
    sheen.animate().translationX(width * 1.1f).setDuration(650).withEndAction { sheen.visibility = View.INVISIBLE }.start()
}

/** Kacheln einer Reihe */
class CardAdapter(
    private val style: CardStyle,
    private val onFocus: (Card, View) -> Unit,
    private val onClick: (Card, View) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    var cards: List<Card> = emptyList()
        private set

    fun submit(list: List<Card>) { cards = list; notifyDataSetChanged() }

    class VH(val root: LinearLayout, val frame: FrameLayout, val img: ImageView, val label: TextView, val sheen: View,
             val bar: ProgressBar, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    private fun size(c: Context): Pair<Int, Int> = when (style) {
        CardStyle.POSTER -> dp(c, 124) to dp(c, 186)
        CardStyle.WIDE -> dp(c, 232) to dp(c, 130)
        CardStyle.CHANNEL -> dp(c, 196) to dp(c, 110)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val (w, h) = size(c)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            clipChildren = false; clipToPadding = false
            layoutParams = RecyclerView.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(c, 22) }
        }
        val frame = FrameLayout(c).apply {
            setBackgroundResource(R.drawable.card_bg)
            clipToOutline = true
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(w, h)
        }
        val img = ImageView(c).apply {
            scaleType = if (style == CardStyle.CHANNEL) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            if (style == CardStyle.CHANNEL) { val p = dp(c, 22); setPadding(p, p, p, p) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // Text auf der Kachel, wenn kein Bild vorhanden ist (oder „Alle anzeigen“)
        val label = TextView(c).apply {
            type(Ui.T.HEADLINE)
            setTextColor(ContextCompat.getColor(c, R.color.text))
            gravity = Gravity.CENTER
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            val p = dp(c, 12); setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val sheen = View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF))
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams((w * 0.6f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = ContextCompat.getDrawable(c, R.drawable.progress_white)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 4), Gravity.BOTTOM).apply {
                val m = dp(c, 10); setMargins(m, 0, m, m)
            }
        }
        frame.addView(img); frame.addView(label); frame.addView(bar); frame.addView(sheen)
        val title = TextView(c).apply {
            type(Ui.T.SUBHEAD_MEDIUM)
            setTextColor(ContextCompat.getColorStateList(c, R.color.poster_text))
            isDuplicateParentStateEnabled = true
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(c, 2), dp(c, 10), dp(c, 2), 0)
        }
        val sub = TextView(c).apply {
            type(Ui.T.FOOTNOTE)
            setTextColor(ContextCompat.getColor(c, R.color.muted))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(c, 2), dp(c, 2), dp(c, 2), 0)
        }
        root.addView(frame); root.addView(title); root.addView(sub)
        return VH(root, frame, img, label, sheen, bar, title, sub)
    }

    override fun getItemCount() = cards.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val card = cards[position]
        h.title.text = if (card.more) "" else card.title
        h.title.visibility = if (card.more || card.title.isEmpty()) View.INVISIBLE else View.VISIBLE
        h.sub.text = card.sub
        h.sub.visibility = if (card.sub.isEmpty()) View.GONE else View.VISIBLE
        h.bar.visibility = if (card.progress >= 0) View.VISIBLE else View.GONE
        if (card.progress >= 0) h.bar.progress = card.progress
        h.img.setImageDrawable(null)
        val img = card.image
        if (card.more) {
            h.label.text = "Alle anzeigen  ›"
            h.label.visibility = View.VISIBLE
        } else if (!img.isNullOrEmpty()) {
            h.label.visibility = View.GONE
            h.img.load(img) {
                crossfade(true)
                listener(onError = { _, _ -> h.label.text = card.title; h.label.visibility = View.VISIBLE })
            }
        } else {
            h.label.text = card.title
            h.label.visibility = View.VISIBLE
        }
        val real = card.payload != null || card.more
        h.root.isFocusable = real
        if (!real) { h.label.visibility = View.GONE; h.title.visibility = View.INVISIBLE }
        if (!h.root.isFocused) { h.root.scaleX = 1f; h.root.scaleY = 1f; h.frame.translationZ = 0f }
        h.root.setOnClickListener { v -> onClick(card, v) }
        h.root.setOnFocusChangeListener { v, has ->
            v.pivotX = v.width / 2f; v.pivotY = h.frame.height / 2f
            v.focusLift(has, 1.1f, h.frame)
            if (has) { shine(h.sheen, h.frame.width); onFocus(card, v) }
        }
    }
}

/**
 * Senkrechte Liste der Reihen. Jede Reihe hat eine eigene waagerechte Liste.
 * onCardFocus bekommt Reihe, Kachel und Ansicht, damit die Seite mitscrollen kann.
 */
class ShelfAdapter(
    private val onCardFocus: (Int, Card, View) -> Unit,
    private val onCardClick: (Int, Card, View) -> Unit
) : RecyclerView.Adapter<ShelfAdapter.VH>() {

    var shelves: List<Shelf> = emptyList()
        private set
    private val scroll = HashMap<Int, android.os.Parcelable?>()

    /** wird beim Anzeigen einer Reihe aufgerufen (zum Nachladen) */
    var onBindShelf: ((Int) -> Unit)? = null

    fun submit(list: List<Shelf>) { shelves = list; scroll.clear(); notifyDataSetChanged() }

    /** Kacheln einer Reihe austauschen, ohne die Reihe neu aufzubauen */
    fun replaceCards(i: Int, cards: List<Card>) {
        val l = shelves.toMutableList()
        l[i] = l[i].copy(cards = cards, placeholder = false)
        shelves = l
        notifyItemChanged(i, "cards")
    }

    fun removeAt(i: Int) {
        val l = shelves.toMutableList(); l.removeAt(i); shelves = l
        notifyItemRemoved(i)
    }

    fun append(s: Shelf) { shelves = shelves + s; notifyItemInserted(shelves.size - 1) }

    /** Liste tauschen ohne Benachrichtigung (Aufrufer meldet die Änderung selbst) */
    fun setRaw(list: List<Shelf>) { shelves = list }

    class VH(val root: LinearLayout, val title: TextView, val rv: RecyclerView) : RecyclerView.ViewHolder(root) {
        var adapter: CardAdapter? = null
        var style: CardStyle? = null
    }

    override fun getItemViewType(position: Int) = shelves[position].style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(c, 22))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val title = TextView(c).apply {
            type(Ui.T.TITLE3)
            setTextColor(ContextCompat.getColor(c, R.color.text))
            setPadding(dp(c, 48), 0, dp(c, 48), dp(c, 12))
        }
        val rv = RecyclerView(c).apply {
            layoutManager = object : LinearLayoutManager(c, HORIZONTAL, false) {
                // fokussierte Kachel mit Abstand zum Rand halten, wie in der Apple-TV-App
                override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
                    val edge = dp(c, 120)
                    val left = child.left - parent.paddingLeft
                    val right = child.right - (parent.width - parent.paddingRight)
                    val dx = when {
                        left < edge && findFirstVisibleItemPosition() > 0 -> left - edge
                        left < 0 -> left
                        right > -edge -> right + edge
                        else -> 0
                    }
                    if (dx != 0) { if (immediate) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0) }
                    return dx != 0
                }
            }
            clipChildren = false; clipToPadding = false
            setPadding(dp(c, 48), dp(c, 6), dp(c, 48), dp(c, 6))
            itemAnimator = null
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(title); root.addView(rv)
        return VH(root, title, rv)
    }

    override fun getItemCount() = shelves.size

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && h.adapter != null) {
            h.adapter?.submit(shelves[position].cards)
            return
        }
        onBindViewHolder(h, position)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = shelves[position]
        onBindShelf?.invoke(position)
        h.title.text = s.title
        h.title.visibility = if (s.title.isEmpty()) View.GONE else View.VISIBLE
        val a = CardAdapter(s.style, { card, v -> onCardFocus(h.bindingAdapterPosition, card, v) }, { card, v -> onCardClick(h.bindingAdapterPosition, card, v) })
        a.submit(s.cards)
        h.adapter = a; h.style = s.style
        h.rv.adapter = a
        scroll[position]?.let { h.rv.layoutManager?.onRestoreInstanceState(it) } ?: h.rv.scrollToPosition(0)
    }

    override fun onViewRecycled(h: VH) {
        val p = h.bindingAdapterPosition
        if (p >= 0) scroll[p] = h.rv.layoutManager?.onSaveInstanceState()
        super.onViewRecycled(h)
    }
}

/** Senkrechte Liste: Seite scrollt selbst (Reihe springt an feste Höhe), nicht der Standard */
class ShelfLayoutManager(c: Context) : LinearLayoutManager(c, VERTICAL, false) {
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean) = false
}

