package com.uow.scan

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.uow.scan.util.TerminatorDemoData
import com.uow.scan.util.TerminatorDemoData.TermApp
import com.uow.scan.util.TerminatorManager

/**
 * Terminator per-app detail (S'CAN V4 handoff: terminator-detail.jsx). Frontend demo.
 * Splits controls honestly: "Enforced by S'CAN" (network 4-state + block trackers, the tunnel)
 * vs "Handed to Android" (permission revokes via Settings, or Shizuku in Auto mode).
 */
class TerminatorAppDetailActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var app: TermApp
    private var mode = "manual"
    private var net = "allowed"
    private val perms = HashMap<String, String>()
    private var trkBlocked = false

    private var cAccent = 0; private var cWarn = 0; private var cBad = 0; private var cOk = 0
    private var cFg0 = 0; private var cFg1 = 0; private var cFg2 = 0; private var cFg3 = 0
    private var cSurf2 = 0; private var cSurf3 = 0; private var cHairline = 0; private var cHairline2 = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminator_app_detail)
        fun c(id: Int) = ContextCompat.getColor(this, id)
        cAccent = c(R.color.v4_accent); cWarn = c(R.color.v4_warn); cBad = c(R.color.v4_bad); cOk = c(R.color.v4_ok)
        cFg0 = c(R.color.v4_fg0); cFg1 = c(R.color.v4_fg1); cFg2 = c(R.color.v4_fg2); cFg3 = c(R.color.v4_fg3)
        cSurf2 = c(R.color.v4_surf2); cSurf3 = c(R.color.v4_surf3)
        cHairline = c(R.color.v4_hairline); cHairline2 = c(R.color.v4_hairline2)

        val id = intent.getStringExtra("appId")
        app = TerminatorDemoData.apps().find { it.id == id } ?: TerminatorDemoData.apps()[0]
        mode = TerminatorManager.getMode(this)
        net = app.net
        app.sensors.forEach { perms[it.s] = it.perm }

        root = findViewById(R.id.detailRoot)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        root.removeAllViews()
        addHeader()
        addLabel("Background timeline · today", cFg3)
        addTimeline()
        addLabel("Enforced by S'CAN", cAccent)
        addNetworkCard()
        if (app.trackerCalls > 0) addTrackerCard()
        if (app.sensors.isNotEmpty()) {
            addLabel(if (mode == "auto") "Auto revoke via Shizuku" else "Handed to Android", cFg2)
            addRevokeCard()
        }
        addFooter()
    }

    private fun addHeader() {
        val card = card()
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tile(app.mono, app.brand, 50, 20f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = app.name; setTextColor(cFg0); textSize = 18f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = app.pkg; setTextColor(cFg3); textSize = 10.5f; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(13) })
        card.addView(row)

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val statData = listOf(
            Triple("Background", TerminatorDemoData.fmtBytes(app.bgBytes), if (app.bgBytes >= app.fgBytes) cWarn else cFg1),
            Triple("Foreground", TerminatorDemoData.fmtBytes(app.fgBytes), cFg1),
            Triple("Trackers", app.trackerCalls.toString(), if (app.trackerCalls > 0) cBad else cOk),
        )
        statData.forEachIndexed { i, (l, v, col2) ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9))
                background = roundedBg(cSurf3, cHairline, 1, 10f)
            }
            tile.addView(TextView(this).apply { text = l.uppercase(); setTextColor(cFg3); textSize = 9f; letterSpacing = 0.06f; setTypeface(typeface, Typeface.BOLD) })
            tile.addView(TextView(this).apply { text = v; setTextColor(col2); textSize = 14f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(0, dp(3), 0, 0) })
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(8)
            stats.addView(tile, lp)
        }
        card.addView(stats, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(14) })
        addView(card, 8)
    }

    private fun addTimeline() {
        val card = card(); card.setPadding(dp(16), dp(14), dp(16), dp(14))
        app.events.forEachIndexed { i, e ->
            val m = TerminatorDemoData.sensorMeta(e.s)
            val c = ContextCompat.getColor(this, m.colorRes)
            val rowWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowWrap.addView(ImageView(this).apply {
                setImageResource(m.iconRes); setColorFilter(c)
                background = roundedBg(withAlpha(c, 0x1A), withAlpha(c, 0x44), 1, 9f)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val noteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            noteRow.addView(TextView(this).apply { text = e.note; setTextColor(cFg0); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD) })
            if (e.bg) noteRow.addView(TextView(this).apply {
                text = "BG"; setTextColor(cWarn); textSize = 8.5f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(5), dp(1), dp(5), dp(1)); background = roundedBg(withAlpha(cWarn, 0x1A), withAlpha(cWarn, 0x33), 1, 4f)
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
            }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) } })
            col.addView(noteRow)
            col.addView(TextView(this).apply {
                text = e.t + (if (e.bytes > 0) " · ${TerminatorDemoData.fmtBytes(e.bytes)}" else "")
                setTextColor(cFg3); textSize = 10.5f; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); setPadding(0, dp(2), 0, 0)
            })
            rowWrap.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(12) })
            card.addView(rowWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { if (i > 0) it.topMargin = dp(14) })
        }
        addView(card, 0)
    }

    private fun addNetworkCard() {
        val card = card(); card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.addView(TextView(this).apply { text = "Network"; setTextColor(cFg0); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD) })
        card.addView(TextView(this).apply {
            text = "Cut through S'CAN's tunnel. The app keeps running, it just loses the connection you choose."
            setTextColor(cFg3); textSize = 10.5f; setPadding(0, dp(3), 0, dp(11))
        })
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TerminatorDemoData.NET_ORDER.forEachIndexed { i, s ->
            val m = TerminatorDemoData.netMeta(s)
            val c = ContextCompat.getColor(this, m.colorRes)
            val on = net == s
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(9), dp(4), dp(9))
                background = roundedBg(
                    if (on) (if (m.enforced) withAlpha(c, 0x1F) else cSurf3) else Color.TRANSPARENT,
                    if (on) (if (m.enforced) withAlpha(c, 0x55) else cHairline2) else cHairline, 1, 10f
                )
            }
            val content = if (on) (if (m.enforced) c else cFg1) else cFg3
            cell.addView(ImageView(this).apply { setImageResource(m.iconRes); setColorFilter(content); layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)) })
            cell.addView(TextView(this).apply {
                text = m.label; setTextColor(content); textSize = 9f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0)
            })
            cell.setOnClickListener { net = s; render(); toast("${app.name}: ${m.label}") }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(5)
            seg.addView(cell, lp)
        }
        card.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(card, 0)
    }

    private fun addTrackerCard() {
        val card = card(); card.setPadding(dp(14), dp(14), dp(14), dp(14))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_glyph_trackers); setColorFilter(if (trkBlocked) cAccent else cFg3)
            background = roundedBg(if (trkBlocked) withAlpha(cAccent, 0x24) else cSurf3, if (trkBlocked) withAlpha(cAccent, 0x4D) else cHairline, 1, 10f)
            setPadding(dp(9), dp(9), dp(9), dp(9)); layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = "Block this app's trackers"; setTextColor(cFg0); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        val n = app.trackers.size
        col.addView(TextView(this).apply { text = "${app.trackerCalls} calls to $n ${if (n == 1) "company" else "companies"}. Sinkholed on the tunnel."; setTextColor(cFg3); textSize = 10.5f })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(12) })
        val sw = SwitchCompat(this).apply {
            isChecked = trkBlocked
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) cAccent else cSurf3)
            setOnCheckedChangeListener { _, ck -> trkBlocked = ck; render(); toast(if (ck) "Blocked trackers for ${app.name}" else "Trackers allowed") }
        }
        row.addView(sw)
        card.addView(row)
        if (app.trackers.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(11), 0, 0) }
            app.trackers.forEach { key ->
                val t = TerminatorDemoData.TRACKERS[key] ?: return@forEach
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(3), dp(8), dp(3)); background = roundedBg(cSurf3, cHairline2, 1, 6f)
                }
                chip.addView(View(this).apply {
                    background = roundedBg(if (t.cat == "Advertising") cBad else cWarn, Color.TRANSPARENT, 0, 2f)
                    layoutParams = LinearLayout.LayoutParams(dp(4), dp(4))
                })
                chip.addView(TextView(this).apply { text = "  ${t.name} "; setTextColor(cFg1); textSize = 10f; setTypeface(typeface, Typeface.BOLD) })
                chip.addView(TextView(this).apply { text = t.cat; setTextColor(cFg3); textSize = 10f })
                val wrap = LinearLayout(this); wrap.setPadding(0, 0, 0, dp(6))
                wrap.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                chips.addView(wrap)
            }
            card.addView(chips)
        }
        addView(card, 9)
    }

    private fun addRevokeCard() {
        val card = card(); card.setPadding(0, 0, 0, 0)
        app.sensors.forEachIndexed { i, s ->
            val c = ContextCompat.getColor(this, TerminatorDemoData.sensorMeta(s.s).colorRes)
            val revoked = perms[s.s] == "revoked"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            row.addView(ImageView(this).apply {
                setImageResource(TerminatorDemoData.sensorMeta(s.s).iconRes); setColorFilter(c)
                background = roundedBg(withAlpha(c, 0x1A), withAlpha(c, 0x40), 1, 9f)
                setPadding(dp(8), dp(8), dp(8), dp(8)); layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = TerminatorDemoData.sensorMeta(s.s).label; setTextColor(cFg0); textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
            col.addView(TextView(this).apply {
                text = if (revoked) (if (mode == "auto") "Auto revoked by Shizuku" else "Revoked in Settings")
                else (if (mode == "auto") "Auto-revoked by Shizuku" else "Opens Settings to revoke")
                setTextColor(if (revoked) cOk else cFg3); textSize = 10.5f; setPadding(0, dp(2), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(12) })
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = roundedBg(if (revoked) withAlpha(cOk, 0x1A) else cSurf3, if (revoked) withAlpha(cOk, 0x44) else cHairline2, 1, 9f)
            }
            btn.addView(ImageView(this).apply {
                setImageResource(if (revoked) R.drawable.ic_glyph_check else if (mode == "auto") R.drawable.ic_terminator else R.drawable.ic_glyph_arrow_right)
                setColorFilter(if (revoked) cOk else cFg1); layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
            })
            btn.addView(TextView(this).apply { text = if (revoked) "Revoked" else "Revoke"; setTextColor(if (revoked) cOk else cFg1); textSize = 11f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(6), 0, 0, 0) })
            if (!revoked) btn.setOnClickListener { perms[s.s] = "revoked"; render(); toast(if (mode == "auto") "Auto revoked ${s.s}" else "Opening Settings to revoke ${s.s}") }
            row.addView(btn)
            card.addView(row)
            if (i < app.sensors.size - 1) card.addView(View(this).apply { setBackgroundColor(cHairline); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) })
        }
        addView(card, 0)
        addView(TextView(this).apply {
            text = if (mode == "auto") "Shizuku revokes the permission for you, no trip to Settings."
            else "S'CAN cannot revoke permissions itself. It opens the exact Android Settings page so you can."
            setTextColor(cFg3); textSize = 10f; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); setPadding(dp(2), dp(9), dp(2), 0)
        }, 0)
    }

    private fun addFooter() {
        addView(TextView(this).apply {
            text = "Terminator cannot reach inside ${app.name}. It cuts the network through S'CAN's tunnel and routes you to revoke the rest."
            setTextColor(cFg3); textSize = 10.5f; gravity = Gravity.CENTER; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(dp(8), dp(20), dp(8), 0); setLineSpacing(dp(3).toFloat(), 1f)
        }, 0)
    }

    // ── helpers ──
    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = roundedBg(cSurf2, cHairline, 1, 16f) }
        return c
    }

    private fun addLabel(text: String, color: Int) {
        root.addView(TextView(this).apply {
            this.text = text.uppercase(); setTextColor(color); textSize = 10.5f; letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(22); it.bottomMargin = dp(10) })
    }

    private fun addView(v: View, topMarginDp: Int) {
        root.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(topMarginDp) })
    }

    private fun tile(mono: String, brand: Int, sizeDp: Int, textSp: Float): TextView = TextView(this).apply {
        text = mono; setTextColor(brand); textSize = textSp; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        background = roundedBg(withAlpha(brand, 0x22), withAlpha(brand, 0x44), 1, sizeDp * 0.28f)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    private fun roundedBg(fill: Int, stroke: Int, strokeDp: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(radiusDp).toFloat(); setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
