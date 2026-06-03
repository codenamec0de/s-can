package com.uow.scan

import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
 * Terminator (Privacy Enforcer) — demo screen built from the designer's S'CAN V4 handoff
 * (terminator-screen.jsx). Frontend only: drives from [TerminatorDemoData]; no backend
 * enforcement is wired yet. Real Shizuku detection is preserved from the prior build.
 *
 * Compliance: the only actions offered are "cut network via S'CAN's tunnel" (Cut data / Block
 * background / Block all / Trackers only) and "revoke" which routes to Settings or Shizuku.
 * No kill / force-stop. See [TerminatorDemoData] and the design brief.
 */
class TerminatorActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var segGuided: TextView
    private lateinit var segAuto: TextView
    private lateinit var heroHeadline: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroSensors: LinearLayout
    private lateinit var lockdownSwitch: SwitchCompat
    private lateinit var lockdownBanner: TextView
    private lateinit var caughtList: LinearLayout
    private lateinit var netSummary: TextView
    private lateinit var netList: LinearLayout
    private lateinit var trackerList: LinearLayout
    private lateinit var btnRules: TextView
    private lateinit var ivShizukuStatus: ImageView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvShizukuSub: TextView
    private lateinit var btnShizukuSetup: TextView

    private var mode = "manual"          // "manual" (Guided) | "auto" (Shizuku)
    private var lockdown = false
    private val apps = TerminatorDemoData.apps().toMutableList()
    private val rules = HashMap(TerminatorDemoData.RULES_DEFAULT)

    // resolved theme colors
    private var cAccent = 0; private var cWarn = 0; private var cBad = 0; private var cOk = 0
    private var cFg0 = 0; private var cFg1 = 0; private var cFg2 = 0; private var cFg3 = 0; private var cFg4 = 0
    private var cSurf2 = 0; private var cSurf3 = 0; private var cHairline = 0; private var cHairline2 = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminator)
        resolveColors()
        bindViews()
        mode = TerminatorManager.getMode(this)
        setupListeners()
        updateModeSeg()
        render()
    }

    private fun resolveColors() {
        fun c(id: Int) = ContextCompat.getColor(this, id)
        cAccent = c(R.color.v4_accent); cWarn = c(R.color.v4_warn); cBad = c(R.color.v4_bad); cOk = c(R.color.v4_ok)
        cFg0 = c(R.color.v4_fg0); cFg1 = c(R.color.v4_fg1); cFg2 = c(R.color.v4_fg2); cFg3 = c(R.color.v4_fg3); cFg4 = c(R.color.v4_fg4)
        cSurf2 = c(R.color.v4_surf2); cSurf3 = c(R.color.v4_surf3)
        cHairline = c(R.color.v4_hairline); cHairline2 = c(R.color.v4_hairline2)
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        segGuided = findViewById(R.id.segGuided)
        segAuto = findViewById(R.id.segAuto)
        heroHeadline = findViewById(R.id.heroHeadline)
        heroSub = findViewById(R.id.heroSub)
        heroSensors = findViewById(R.id.heroSensors)
        lockdownSwitch = findViewById(R.id.lockdownSwitch)
        lockdownBanner = findViewById(R.id.lockdownBanner)
        caughtList = findViewById(R.id.caughtList)
        netSummary = findViewById(R.id.netSummary)
        netList = findViewById(R.id.netList)
        trackerList = findViewById(R.id.trackerList)
        btnRules = findViewById(R.id.btnRules)
        ivShizukuStatus = findViewById(R.id.ivShizukuStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        tvShizukuSub = findViewById(R.id.tvShizukuSub)
        btnShizukuSetup = findViewById(R.id.btnShizukuSetup)

        lockdownBanner.background = roundedBg(withAlpha(cAccent, 0x24), withAlpha(cAccent, 0x4D), 1, 11f)
        lockdownSwitch.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        segGuided.setOnClickListener { setMode("manual") }
        segAuto.setOnClickListener { setMode("auto") }
        lockdownSwitch.setOnCheckedChangeListener { _, checked -> lockdown = checked; render() }
        btnRules.setOnClickListener { showRulesSheet() }
        btnShizukuSetup.setOnClickListener { openShizukuSetup() }
    }

    private fun setMode(m: String) {
        if (mode == m) return
        mode = m
        TerminatorManager.setMode(this, m)
        updateModeSeg()
        updateShizuku()
        render()
    }

    private fun updateModeSeg() {
        listOf(segGuided to "manual", segAuto to "auto").forEach { (tv, key) ->
            val on = mode == key
            tv.background = if (on) roundedBg(cAccent, cAccent, 0, 7f) else null
            tv.setTextColor(if (on) ContextCompat.getColor(this, R.color.v4_bg) else cFg2)
        }
        (segGuided.parent as View).background = roundedBg(cSurf3, cHairline2, 1, 9f)
    }

    // ───────────────────────── render ─────────────────────────

    private fun render() {
        val caught = apps.count { a -> a.sensors.any { it.background } }
        heroHeadline.text = "$caught apps reached in while you weren't looking."
        heroSub.text = "Caught using your camera, mic, or location in the background today."
        lockdownSwitch.trackTintList = android.content.res.ColorStateList.valueOf(if (lockdown) cAccent else cSurf3)
        if (lockdownSwitch.isChecked != lockdown) lockdownSwitch.isChecked = lockdown

        renderHeroSensors()
        lockdownBanner.visibility = if (lockdown) View.VISIBLE else View.GONE
        if (lockdown) lockdownBanner.text = "Lockdown on. Background data cut for ${apps.size} watched apps."

        renderCaught()
        renderNetwork()
        renderTrackers()
        updateShizuku()
    }

    private fun eff(a: TermApp): String = if (lockdown && a.net == "allowed") "bg" else a.net

    private fun renderHeroSensors() {
        heroSensors.removeAllViews()
        val counts = linkedMapOf(
            "camera" to apps.sumOf { a -> a.sensors.count { it.s == "camera" && it.background } },
            "mic" to apps.sumOf { a -> a.sensors.count { it.s == "mic" && it.background } },
            "location" to apps.sumOf { a -> a.sensors.count { it.s == "location" && it.background } },
        )
        counts.entries.forEachIndexed { i, (s, n) ->
            val meta = TerminatorDemoData.sensorMeta(s)
            val c = ContextCompat.getColor(this, meta.colorRes)
            val lit = n > 0
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBg(if (lit) withAlpha(c, 0x14) else cSurf3, if (lit) withAlpha(c, 0x40) else cHairline, 1, 10f)
            }
            chip.addView(ImageView(this).apply {
                setImageResource(meta.iconRes); setColorFilter(if (lit) c else cFg4)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            chip.addView(TextView(this).apply {
                text = n.toString(); setTextColor(if (lit) cFg0 else cFg4)
                textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(7), 0, 0, 0)
            })
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(8)
            heroSensors.addView(chip, lp)
        }
    }

    private fun renderCaught() {
        caughtList.removeAllViews()
        val offenders = apps.sortedByDescending { it.sensors.isNotEmpty() }
        offenders.forEach { app ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(cSurf2, if (app.overreach) withAlpha(cBad, 0x44) else cHairline, 1, 16f)
            }
            card.setOnClickListener { openDetail(app) }
            // top row
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(11))
            }
            top.addView(appTile(app, 40, 15f))
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(TextView(this).apply {
                text = app.name; setTextColor(cFg0); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (app.overreach) nameRow.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_glyph_warn); setColorFilter(cBad)
                layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).also { it.marginStart = dp(6) }
            })
            nameCol.addView(nameRow)
            nameCol.addView(pillView(app), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) })
            top.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(12) })
            top.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_glyph_chevron); setColorFilter(cFg3)
                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15))
            })
            card.addView(top)

            // evidence (stacked)
            val ev = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(11)) }
            app.sensors.forEach { s ->
                val m = TerminatorDemoData.sensorMeta(s.s)
                ev.addView(evidenceChip(m.iconRes, ContextCompat.getColor(this, m.colorRes),
                    "${m.label} · ${s.detail}${if (s.background) " · background" else ""}"))
            }
            if (app.bgBytes > 0) ev.addView(evidenceChip(R.drawable.ic_glyph_data, cWarn, "Background data · ${TerminatorDemoData.fmtBytes(app.bgBytes)}"))
            if (app.sensors.isEmpty() && app.trackerCalls > 0) ev.addView(evidenceChip(R.drawable.ic_glyph_trackers, cBad, "${app.trackerCalls} tracker calls"))
            card.addView(ev)

            // actions
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(13)) }
            if (app.sensors.isNotEmpty()) {
                actions.addView(actionColumn(
                    chip = revokeChip(app),
                    caption = if (mode == "auto") "Auto-revoked by Shizuku" else "Opens Settings to revoke"
                ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            actions.addView(actionColumn(
                chip = cutDataChip(app),
                caption = "Enforced by S'CAN's tunnel"
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { if (app.sensors.isNotEmpty()) it.marginStart = dp(8) })
            card.addView(actions)

            caughtList.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(9) })
        }
    }

    private fun renderNetwork() {
        val blocking = apps.count { eff(it) != "allowed" }
        netSummary.text = "Blocking background data for $blocking apps. About ${TerminatorDemoData.fmtBytes(TerminatorDemoData.SAVED_BYTES)} saved this week."
        netList.removeAllViews()
        apps.forEachIndexed { i, app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(10), dp(13), dp(10))
            }
            row.addView(appTile(app, 30, 12f))
            row.addView(TextView(this).apply {
                text = app.name; setTextColor(cFg0); textSize = 12.5f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(11) })
            row.addView(cutDataChip(app))
            netList.addView(row)
            if (i < apps.size - 1) netList.addView(divider())
        }
    }

    private fun renderTrackers() {
        trackerList.removeAllViews()
        val trk = apps.filter { it.trackerCalls > 0 }.sortedByDescending { it.trackerCalls }
        trk.forEachIndexed { i, app ->
            val blocked = eff(app) == "all" || eff(app) == "trackers"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(10), dp(13), dp(10))
            }
            row.addView(appTile(app, 30, 12f))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = app.name; setTextColor(cFg0); textSize = 12.5f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            col.addView(TextView(this).apply {
                text = "${app.trackerCalls} calls · ${app.trackers.size} ${if (app.trackers.size == 1) "company" else "companies"}"
                setTextColor(cFg3); textSize = 10f
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(11) })

            val toggle = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = roundedBg(if (blocked) withAlpha(cAccent, 0x24) else cSurf3, if (blocked) withAlpha(cAccent, 0x4D) else cHairline2, 1, 8f)
            }
            toggle.addView(ImageView(this).apply {
                setImageResource(if (blocked) R.drawable.ic_glyph_check else R.drawable.ic_glyph_block)
                setColorFilter(if (blocked) cAccent else cFg2)
                layoutParams = LinearLayout.LayoutParams(dp(11), dp(11))
            })
            toggle.addView(TextView(this).apply {
                text = if (blocked) "Blocked" else "Block"; setTextColor(if (blocked) cAccent else cFg2)
                textSize = 10.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(5), 0, 0, 0)
            })
            toggle.setOnClickListener {
                app.net = if (blocked) "allowed" else "trackers"
                render()
            }
            row.addView(toggle)
            trackerList.addView(row)
            if (i < trk.size - 1) trackerList.addView(divider())
        }
    }

    // ───────────────────────── small view builders ─────────────────────────

    private fun appTile(app: TermApp, sizeDp: Int, textSp: Float): TextView = TextView(this).apply {
        text = app.mono; setTextColor(app.brand); textSize = textSp
        setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        background = roundedBg(withAlpha(app.brand, 0x22), withAlpha(app.brand, 0x44), 1, (sizeDp * 0.3f))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun pillView(app: TermApp): TextView {
        val p = TerminatorDemoData.cardPill(eff(app))
        val c = ContextCompat.getColor(this, p.colorRes)
        return TextView(this).apply {
            text = p.label.uppercase(); setTextColor(c); textSize = 9.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.05f
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = roundedBg(withAlpha(c, 0x1A), withAlpha(c, 0x33), 1, 5f)
        }
    }

    private fun evidenceChip(iconRes: Int, color: Int, label: String): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBg(withAlpha(cFg2, 0x12), withAlpha(color, 0x40), 1, 6f)
        }
        chip.addView(ImageView(this).apply {
            setImageResource(iconRes); setColorFilter(color)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        })
        chip.addView(TextView(this).apply {
            text = label; setTextColor(cFg1); textSize = 10f; setPadding(dp(5), 0, 0, 0)
        })
        val wrap = LinearLayout(this)
        wrap.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.setPadding(0, 0, 0, dp(6))
        return wrap
    }

    private fun actionColumn(chip: View, caption: String): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(this).apply {
            text = caption; setTextColor(cFg3); textSize = 8.5f; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return col
    }

    private fun cutDataChip(app: TermApp): View {
        val state = eff(app)
        val m = TerminatorDemoData.netMeta(state)
        val c = ContextCompat.getColor(this, m.colorRes)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = roundedBg(if (m.enforced) withAlpha(c, 0x1F) else cSurf3, if (m.enforced) withAlpha(c, 0x55) else cHairline2, 1, 9f)
        }
        chip.addView(ImageView(this).apply {
            setImageResource(m.iconRes); setColorFilter(if (m.enforced) c else cFg2)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        })
        chip.addView(TextView(this).apply {
            text = m.label; setTextColor(if (m.enforced) c else cFg1); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(6), 0, 0, 0)
        })
        chip.setOnClickListener {
            app.net = TerminatorDemoData.nextNet(eff(app))
            render()
        }
        return chip
    }

    private fun revokeChip(app: TermApp): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = roundedBg(cSurf3, cHairline2, 1, 9f)
        }
        chip.addView(ImageView(this).apply {
            setImageResource(if (mode == "auto") R.drawable.ic_terminator else R.drawable.ic_glyph_arrow_right)
            setColorFilter(cFg1); layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        })
        chip.addView(TextView(this).apply {
            text = "Revoke"; setTextColor(cFg1); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(6), 0, 0, 0)
        })
        chip.setOnClickListener {
            val sensor = app.sensors.firstOrNull()?.s ?: "permission"
            toast(if (mode == "auto") "Auto revoked $sensor for ${app.name}" else "Opening Settings to revoke $sensor")
        }
        return chip
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(cHairline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    // ───────────────────────── rules sheet ─────────────────────────

    private fun showRulesSheet() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(26))
            background = roundedBg(ContextCompat.getColor(this@TerminatorActivity, R.color.v4_surf1), cHairline2, 1, 22f)
        }
        root.addView(TextView(this).apply { text = "Lockdown rules"; setTextColor(cFg0); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "Cut background data for watched apps automatically. All enforced by the tunnel."
            setTextColor(cFg2); textSize = 12f; setPadding(0, dp(8), 0, dp(8))
        })
        TerminatorDemoData.RULES.forEach { rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(11), 0, dp(11))
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = rule.label; setTextColor(cFg0); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            col.addView(TextView(this).apply { text = rule.sub; setTextColor(cFg3); textSize = 10.5f })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = SwitchCompat(this).apply {
                isChecked = rules[rule.key] == true
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) cAccent else cSurf3)
                setOnCheckedChangeListener { _, ck -> rules[rule.key] = ck; trackTintList = android.content.res.ColorStateList.valueOf(if (ck) cAccent else cSurf3) }
            }
            row.addView(sw)
            root.addView(row)
        }
        val dialog = Dialog(this)
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    private fun openDetail(app: TermApp) {
        startActivity(Intent(this, TerminatorAppDetailActivity::class.java).putExtra("appId", app.id))
    }

    // ───────────────────────── Shizuku (real detection, preserved) ─────────────────────────

    private fun updateShizuku() {
        val installed = isShizukuInstalled()
        val running = installed && isShizukuRunning()
        when {
            !installed -> {
                ivShizukuStatus.setColorFilter(cFg3)
                tvShizukuStatus.text = "Shizuku not installed"
                tvShizukuSub.text = "Install Shizuku to let Terminator revoke permissions for you, no root needed."
                btnShizukuSetup.text = "Learn how"
            }
            !running -> {
                ivShizukuStatus.setColorFilter(cWarn)
                tvShizukuStatus.text = "Shizuku installed, not running"
                tvShizukuSub.text = "Start Shizuku once after each reboot with wireless debugging to enable auto revoke."
                btnShizukuSetup.text = "Start Shizuku"
            }
            else -> {
                ivShizukuStatus.setColorFilter(cOk)
                tvShizukuStatus.text = "Shizuku active"
                tvShizukuSub.text = "Terminator can revoke camera, mic, and location for you, no trip to Settings."
                btnShizukuSetup.text = "Manage"
            }
        }
    }

    private fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun isShizukuRunning(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0).applicationInfo?.enabled == true
    } catch (e: Exception) { false }

    private fun openShizukuSetup() {
        if (!isShizukuInstalled()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            }
        } else {
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                ?: toast("Could not open Shizuku")
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizuku()
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    private fun roundedBg(fill: Int, stroke: Int, strokeDp: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
