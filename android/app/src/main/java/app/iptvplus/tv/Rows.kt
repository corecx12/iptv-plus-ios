package app.iptvplus.tv

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Eine Zeile in einer Liste (Kategorie, Sender, Film, Sendung …) */
data class Row(
    val title: String,
    val sub: String = "",
    val sub2: String = "",
    val icon: String? = null,
    val progress: Int = -1,       // 0..1000, -1 = keine Leiste
    val poster: Boolean = false,
    val payload: Any? = null,
    val bold: Boolean = false
)

/** Apple-TV-Fokus: Element hebt sich leicht an (größer + Schatten) */
fun View.focusLift(has: Boolean, scale: Float, lift: View = this) {
    animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(160).start()
    val z = if (has) 10f * resources.displayMetrics.density else 0f
    lift.animate().translationZ(z).setDuration(160).start()
}

class RowAdapter(
    private val showIcons: Boolean,
    private val onFocus: ((View) -> Unit)? = null,
    private val onClick: (Int, Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    var rows: List<Row> = emptyList()
        private set
    var selected: Int = -1
    /** Höhe eines Posters im Raster (Pixel), wird von der Activity passend zur Spaltenbreite gesetzt */
    var posterHeight: Int = 0

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout, val img: ImageView, val title: TextView, val sub: TextView, val sub2: TextView, val bar: ProgressBar?) :
        RecyclerView.ViewHolder(root)

    private fun dp(v: View, x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), v.resources.displayMetrics).toInt()

    override fun getItemViewType(position: Int) = if (showIcons && rows[position].poster) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        if (viewType == 1) createPoster(parent) else createRow(parent)

    private fun text(parent: ViewGroup, size: Float, color: Int, medium: Boolean = false) = TextView(parent.context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(ContextCompat.getColorStateList(parent.context, color))
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isDuplicateParentStateEnabled = true
    }

    /** Listenzeile: abgerundet, fokussiert weiß mit dunkler Schrift */
    private fun createRow(parent: ViewGroup): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setBackgroundResource(R.drawable.focus_bg)
            setPadding(dp(parent, 14), dp(parent, 9), dp(parent, 14), dp(parent, 9))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(parent, 4)
            }
        }
        val img = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.logo_bg)
            val p = dp(parent, 4); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(parent, 64), dp(parent, 44)).apply { marginEnd = dp(parent, 14) }
        }
        val texts = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = text(parent, 17f, R.color.cell_text, medium = true)
        val sub = text(parent, 14f, R.color.cell_sub)
        val sub2 = text(parent, 13f, R.color.cell_sub)
        val bar = ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = ContextCompat.getDrawable(parent.context, R.drawable.progress_apple)
            layoutParams = LinearLayout.LayoutParams(dp(parent, 220), dp(parent, 4)).apply { topMargin = dp(parent, 6); bottomMargin = dp(parent, 2) }
        }
        texts.addView(title); texts.addView(sub); texts.addView(bar); texts.addView(sub2)
        root.addView(img); root.addView(texts)
        return VH(root, img, title, sub, sub2, bar)
    }

    /** Poster-Kachel für Filme und Serien: abgerundetes Bild, Titel darunter */
    private fun createPoster(parent: ViewGroup): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            clipChildren = false
            clipToPadding = false
            setPadding(dp(parent, 8), dp(parent, 8), dp(parent, 8), dp(parent, 14))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val img = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.poster_bg)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 160))
        }
        val title = text(parent, 14f, R.color.poster_text, medium = true).apply {
            setPadding(dp(parent, 2), dp(parent, 8), dp(parent, 2), 0)
        }
        val sub = text(parent, 12f, R.color.cell_sub).apply {
            setTextColor(ContextCompat.getColor(parent.context, R.color.muted))
            setPadding(dp(parent, 2), dp(parent, 1), dp(parent, 2), 0)
        }
        val sub2 = TextView(parent.context).apply { visibility = View.GONE }
        root.addView(img); root.addView(title); root.addView(sub)
        return VH(root, img, title, sub, sub2, null)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        val isPoster = getItemViewType(position) == 1
        h.title.text = r.title
        h.title.paint.isFakeBoldText = r.bold
        h.sub.text = r.sub
        h.sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        h.sub2.text = r.sub2
        if (!isPoster) h.sub2.visibility = if (r.sub2.isEmpty()) View.GONE else View.VISIBLE
        h.bar?.let { bar ->
            bar.visibility = if (r.progress >= 0) View.VISIBLE else View.GONE
            if (r.progress >= 0) bar.progress = r.progress
        }
        if (isPoster) {
            if (posterHeight > 0 && h.img.layoutParams.height != posterHeight) {
                h.img.layoutParams = h.img.layoutParams.apply { height = posterHeight }
            }
            if (!r.icon.isNullOrEmpty()) h.img.load(r.icon) { crossfade(true) } else h.img.setImageDrawable(null)
        } else if (showIcons && r.icon != null) {
            h.img.visibility = View.VISIBLE
            if (r.icon.isNotEmpty()) h.img.load(r.icon) { crossfade(false) } else h.img.setImageDrawable(null)
        } else {
            h.img.visibility = View.GONE
        }
        h.root.isSelected = position == selected
        if (!h.root.isFocused) {
            // wiederverwendete Zeilen ohne Fokus wieder flach machen
            h.root.scaleX = 1f; h.root.scaleY = 1f; h.root.translationZ = 0f; h.img.translationZ = 0f
        }
        h.root.setOnClickListener { onClick(h.bindingAdapterPosition, r) }
        h.root.setOnFocusChangeListener { v, has ->
            if (isPoster) v.focusLift(has, 1.08f, h.img) else v.focusLift(has, 1.03f)
            if (has) onFocus?.invoke(v)
        }
    }
}
