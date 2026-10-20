package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/** Vollbild-Suche nach Orten (Open-Meteo-Ortssuche). */
@SuppressLint("ViewConstructor")
class SearchPanel(ctx: Context, private val p: Ui.Palette, private val cb: Callbacks) : LinearLayout(ctx) {

    interface Callbacks {
        fun onPick(place: Place)
        fun onPickLocation()
        fun onClose()
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Number) = Ui.dp(context, v)
    private fun dpf(v: Number) = Ui.dpf(context, v)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var seq = 0

    val input = EditText(ctx)
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val hintView = Ui.tv(ctx, "", 14f, p.muted).apply { setPadding(dp(8), dp(14), dp(8), dp(8)) }

    init {
        orientation = VERTICAL
        setBackgroundColor(p.bg)
        isClickable = true
        setPadding(dp(16), dp(14), dp(16), 0)

        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rect(p.surface, dpf(24), p.border, 1)
            setPadding(dp(6), dp(4), dp(10), dp(4))
            addView(FrameLayout(ctx).apply {
                background = Ui.pressable(p, p.surface, dpf(20))
                addView(GlyphView(ctx, GlyphView.Kind.BACK, p.text), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                setOnClickListener { close() }
            }, LayoutParams(dp(40), dp(40)))
            addView(input.apply {
                background = null
                setTextColor(p.text)
                setHintTextColor(p.muted)
                hint = "Ort suchen"
                textSize = 17f
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }, LayoutParams(0, WRAP, 1f))
        }
        addView(bar, LayoutParams(MATCH, WRAP))

        addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                setPadding(0, dp(12), 0, dp(24))
                addView(hintView)
                addView(list)
            })
        }, LayoutParams(MATCH, 0, 1f))

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                val my = ++seq
                main.removeCallbacksAndMessages(null)
                main.postDelayed({ if (my == seq) query(q, my) }, 300)
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                query(input.text.toString().trim(), ++seq); true
            } else false
        }
        showDefault()
    }

    fun open() {
        input.setText("")
        showDefault()
        visibility = View.VISIBLE
        input.requestFocus()
        input.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        visibility = View.GONE
        cb.onClose()
    }

    private fun showDefault() {
        list.removeAllViews()
        hintView.text = "Stadt, Ort oder Postleitzahl eingeben"
        list.addView(row("Aktueller Standort", "per GPS", GlyphView.Kind.LOCATE) { cb.onPickLocation() })
        val favs = Prefs.favorites(context)
        if (favs.isNotEmpty()) {
            list.addView(Ui.tv(context, "FAVORITEN", 12.5f, p.muted, true).apply {
                letterSpacing = 0.08f
                setPadding(dp(8), dp(18), 0, dp(8))
            })
            for (f in favs) list.addView(row(f.name, f.region, GlyphView.Kind.STAR_FILLED) { cb.onPick(f) })
        }
    }

    private fun query(q: String, my: Int) {
        if (q.length < 2) { showDefault(); return }
        hintView.text = "Suche …"
        io.execute {
            val res = runCatching { WeatherApi.search(q) }
            main.post {
                if (my != seq) return@post
                list.removeAllViews()
                res.onSuccess { places ->
                    hintView.text = if (places.isEmpty()) "Nichts gefunden für „$q“" else ""
                    for (pl in places) list.addView(row(pl.name, pl.region, GlyphView.Kind.PIN) { cb.onPick(pl) })
                }.onFailure {
                    hintView.text = "Suche nicht möglich – keine Internetverbindung?"
                }
            }
        }
    }

    private fun row(name: String, sub: String, icon: GlyphView.Kind, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = Ui.pressable(p, p.bg, dpf(16))
            val ic = if (icon == GlyphView.Kind.STAR_FILLED) p.warn else if (icon == GlyphView.Kind.LOCATE) p.accent else p.muted
            addView(GlyphView(context, icon, ic, 20), LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(Ui.tv(context, name, 16f, p.text, true))
                if (sub.isNotEmpty()) addView(Ui.tv(context, sub, 13f, p.muted), LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            }, LayoutParams(0, WRAP, 1f))
            setOnClickListener {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                onClick()
            }
        }
}
