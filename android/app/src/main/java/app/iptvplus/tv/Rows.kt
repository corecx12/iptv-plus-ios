package app.iptvplus.tv

import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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

class RowAdapter(
    private val showIcons: Boolean,
    private val onFocus: ((View) -> Unit)? = null,
    private val onClick: (Int, Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    var rows: List<Row> = emptyList()
        private set
    var selected: Int = -1

    fun submit(list: List<Row>) {
        rows = list
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout, val img: ImageView, val title: TextView, val sub: TextView, val sub2: TextView, val bar: ProgressBar) :
        RecyclerView.ViewHolder(root)

    private fun dp(v: ViewGroup, x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), v.resources.displayMetrics).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setBackgroundResource(R.drawable.focus_bg)
            setPadding(dp(parent, 10), dp(parent, 7), dp(parent, 10), dp(parent, 7))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val img = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(parent, 56), dp(parent, 40)).apply { marginEnd = dp(parent, 12) }
        }
        val texts = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun tv(size: Float, color: Int) = TextView(parent.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val title = tv(16f, Color.parseColor("#E8EAEE"))
        val sub = tv(13f, Color.parseColor("#AAB3C2"))
        val sub2 = tv(12f, Color.parseColor("#8B93A1"))
        val bar = ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(dp(parent, 220), dp(parent, 4)).apply { topMargin = dp(parent, 4) }
        }
        texts.addView(title); texts.addView(sub); texts.addView(bar); texts.addView(sub2)
        root.addView(img); root.addView(texts)
        return VH(root, img, title, sub, sub2, bar)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.title.text = r.title
        h.title.paint.isFakeBoldText = r.bold
        h.sub.text = r.sub
        h.sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        h.sub2.text = r.sub2
        h.sub2.visibility = if (r.sub2.isEmpty()) View.GONE else View.VISIBLE
        h.bar.visibility = if (r.progress >= 0) View.VISIBLE else View.GONE
        if (r.progress >= 0) h.bar.progress = r.progress
        if (showIcons && r.icon != null) {
            h.img.visibility = View.VISIBLE
            val lp = h.img.layoutParams
            val d = h.root.resources.displayMetrics.density
            lp.width = ((if (r.poster) 46 else 56) * d).toInt()
            lp.height = ((if (r.poster) 68 else 40) * d).toInt()
            h.img.layoutParams = lp
            h.img.scaleType = if (r.poster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (r.icon.isNotEmpty()) h.img.load(r.icon) { crossfade(false) } else h.img.setImageDrawable(null)
        } else {
            h.img.visibility = View.GONE
        }
        h.root.isSelected = position == selected
        h.root.setOnClickListener { onClick(h.bindingAdapterPosition, r) }
        h.root.setOnFocusChangeListener { v, has -> if (has) onFocus?.invoke(v) }
    }
}
