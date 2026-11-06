package app.iptvplus.tv

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Schrift und Schriftstufen im Apple-Stil.
 * Inter (frei, SIL OFL) statt SF Pro: große Größen mit Inter Display und engerer Laufweite,
 * Bedienelemente halbfett – so wie Apple SF Pro Display / SF Pro Text einsetzt.
 */
object Ui {
    lateinit var regular: Typeface; private set
    lateinit var medium: Typeface; private set
    lateinit var semibold: Typeface; private set
    lateinit var bold: Typeface; private set
    lateinit var displaySemibold: Typeface; private set
    lateinit var displayBold: Typeface; private set
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        val am = ctx.applicationContext.assets
        fun load(n: String) = try { Typeface.createFromAsset(am, "fonts/$n.ttf") } catch (e: Exception) { Typeface.DEFAULT }
        regular = load("inter_regular")
        medium = load("inter_medium")
        semibold = load("inter_semibold")
        bold = load("inter_bold")
        displaySemibold = load("inter_display_semibold")
        displayBold = load("inter_display_bold")
        ready = true
    }

    /** Schriftstufe: Größe (sp), Schnitt, Laufweite (em) */
    enum class T(val sp: Float, val face: () -> Typeface, val track: Float) {
        LARGE_TITLE(40f, { displayBold }, -0.024f),
        TITLE1(30f, { displayBold }, -0.021f),
        TITLE2(24f, { displaySemibold }, -0.018f),
        TITLE3(20f, { semibold }, -0.014f),
        HEADLINE(17f, { semibold }, -0.011f),
        BODY(16f, { regular }, -0.008f),
        CALLOUT(15f, { regular }, -0.006f),
        SUBHEAD(14f, { regular }, -0.004f),
        SUBHEAD_MEDIUM(14f, { medium }, -0.004f),
        FOOTNOTE(13f, { regular }, -0.002f),
        CAPTION(12f, { medium }, 0f),
        BUTTON(16f, { semibold }, -0.01f),
        TAB(16f, { semibold }, -0.01f)
    }

    fun style(tv: TextView, t: T) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp)
        tv.typeface = t.face()
        tv.letterSpacing = t.track
        tv.paint.isFakeBoldText = false
        tv.includeFontPadding = false
    }

    /** Alle Texte einer Ansicht (z. B. Dialoge) auf Inter umstellen, ohne Größen zu ändern */
    fun fontify(v: View?) {
        when (v) {
            null -> return
            is TextView -> {
                val tf = v.typeface
                // schon auf Inter gesetzte Texte nicht anfassen
                if (tf != null && (tf === regular || tf === medium || tf === semibold || tf === bold || tf === displaySemibold || tf === displayBold)) return
                val b = tf?.isBold == true || tf?.let { it.style and Typeface.BOLD != 0 } == true
                v.typeface = if (b) semibold else regular
            }
            is ViewGroup -> for (i in 0 until v.childCount) fontify(v.getChildAt(i))
        }
    }
}

fun TextView.type(t: Ui.T): TextView { Ui.style(this, t); return this }
