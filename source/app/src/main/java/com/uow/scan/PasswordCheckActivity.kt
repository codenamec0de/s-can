package com.uow.scan

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.uow.scan.ui.FlowLayout
import com.uow.scan.ui.home.widget.RadarPulseView
import com.uow.scan.util.PasswordStrength
import com.uow.scan.util.PwnedPasswords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private Password Check (S'CAN V4 · Breach Checker). Type/paste any password →
 * a local strength read + a provably-private k-anonymity breach check
 * ([PwnedPasswords]). Phases: input → checking → dual-verdict result (or
 * offline). The password is masked, never persisted, and cleared on leave.
 */
class PasswordCheckActivity : AppCompatActivity() {

    private enum class Phase { INPUT, CHECKING, RESULT, OFFLINE }
    private var phase = Phase.INPUT
    private var breachCount = 0
    private var showPw = false

    private lateinit var phaseInput: View
    private lateinit var phaseChecking: View
    private lateinit var phaseResult: View

    private lateinit var pwField: EditText
    private lateinit var pwInputBox: LinearLayout
    private lateinit var pwEyeIcon: ImageView
    private lateinit var pwBtnCheck: MaterialButton
    private lateinit var pwMeterSlotInput: FrameLayout
    private lateinit var pwMeterSlotResult: FrameLayout
    private lateinit var pwRadar: RadarPulseView

    private lateinit var pwVerdictCard: LinearLayout
    private lateinit var pwVerdictDot: View
    private lateinit var pwVerdictState: TextView
    private lateinit var pwCountBlock: View
    private lateinit var pwBreachCount: TextView
    private lateinit var pwHeadlineBlock: View
    private lateinit var pwVerdictHeadline: TextView
    private lateinit var pwVerdictSub: TextView
    private lateinit var pwGuidanceCard: LinearLayout
    private lateinit var pwGuidanceIcon: ImageView
    private lateinit var pwGuidanceText: TextView
    private lateinit var pwCredStuffing: TextView
    private lateinit var pwBtnRetry: MaterialButton
    private lateinit var pwBtnAnother: MaterialButton

    private var meterInput: View? = null
    private var meterResult: View? = null
    private var inputBorderColor = 0
    private var inputBoxBg: GradientDrawable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_check)
        bind()
        applyStaticStyling()
        setupListeners()
        showPhase(Phase.INPUT, animate = false)
        pwField.requestFocus()
        showKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        pwField.setText("")   // never persist the value
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun bind() {
        phaseInput = findViewById(R.id.phaseInput)
        phaseChecking = findViewById(R.id.phaseChecking)
        phaseResult = findViewById(R.id.phaseResult)
        pwField = findViewById(R.id.pwField)
        pwInputBox = findViewById(R.id.pwInputBox)
        pwEyeIcon = findViewById(R.id.pwEyeIcon)
        pwBtnCheck = findViewById(R.id.pwBtnCheck)
        pwMeterSlotInput = findViewById(R.id.pwMeterSlotInput)
        pwMeterSlotResult = findViewById(R.id.pwMeterSlotResult)
        pwRadar = findViewById(R.id.pwRadar)
        pwVerdictCard = findViewById(R.id.pwVerdictCard)
        pwVerdictDot = findViewById(R.id.pwVerdictDot)
        pwVerdictState = findViewById(R.id.pwVerdictState)
        pwCountBlock = findViewById(R.id.pwCountBlock)
        pwBreachCount = findViewById(R.id.pwBreachCount)
        pwHeadlineBlock = findViewById(R.id.pwHeadlineBlock)
        pwVerdictHeadline = findViewById(R.id.pwVerdictHeadline)
        pwVerdictSub = findViewById(R.id.pwVerdictSub)
        pwGuidanceCard = findViewById(R.id.pwGuidanceCard)
        pwGuidanceIcon = findViewById(R.id.pwGuidanceIcon)
        pwGuidanceText = findViewById(R.id.pwGuidanceText)
        pwCredStuffing = findViewById(R.id.pwCredStuffing)
        pwBtnRetry = findViewById(R.id.pwBtnRetry)
        pwBtnAnother = findViewById(R.id.pwBtnAnother)
    }

    private fun applyStaticStyling() {
        // hero + checking icon tiles (accent-bg rounded), verdict dot is set per-result
        findViewById<FrameLayout>(R.id.pwHeroTile).background = tile(18f)
        findViewById<FrameLayout>(R.id.pwCheckingTile).background = tile(25f) // 50dp → circle
        // input box: surf3 fill + animatable hairline/accent stroke, radius 14
        inputBorderColor = c(R.color.v4_hairline2)
        inputBoxBg = GradientDrawable().apply {
            cornerRadius = dp(14f); setColor(c(R.color.v4_surf3)); setStroke(dp(1f).toInt(), inputBorderColor)
        }
        pwInputBox.background = inputBoxBg
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish(); slideBack() }
        pwBtnCheck.setOnClickListener { runCheck() }
        pwBtnAnother.setOnClickListener { reset() }
        pwBtnRetry.setOnClickListener { runCheck() }
        findViewById<View>(R.id.pwEyeBtn).setOnClickListener { toggleShow() }

        pwField.transformationMethod = PasswordTransformationMethod.getInstance()
        pwField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onPwChanged(s?.toString().orEmpty())
        })
        pwField.setOnEditorActionListener { _, _, _ -> runCheck(); true }

        // build the privacy line with an inline tappable link
        val line = findViewById<TextView>(R.id.pwPrivacyLine)
        val body = getString(R.string.breach_pw_privacy_line)
        val link = getString(R.string.breach_pw_privacy_link)
        val sb = SpannableStringBuilder(body).append("  ")
        val start = sb.length
        sb.append(link)
        sb.setSpan(ForegroundColorSpan(c(R.color.v4_accent)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.text = sb
        line.setOnClickListener { showPrivacySheet() }
    }

    // ───────────────────────── input ─────────────────────────

    private fun onPwChanged(pw: String) {
        val has = pw.isNotEmpty()
        pwMeterSlotInput.visibility = if (has) View.VISIBLE else View.GONE
        if (has) bindMeter(meterInputView(), PasswordStrength.estimate(pw))
        animateInputBorder(if (has) c(R.color.v4_accent_ring) else c(R.color.v4_hairline2))
        val tooShort = pw.length < 4
        pwBtnCheck.isEnabled = !tooShort
        pwBtnCheck.alpha = if (tooShort) 0.45f else 1f
    }

    private fun toggleShow() {
        showPw = !showPw
        val sel = pwField.selectionEnd
        pwField.transformationMethod = if (showPw) null else PasswordTransformationMethod.getInstance()
        pwField.setSelection(sel.coerceIn(0, pwField.text?.length ?: 0))
        pwEyeIcon.imageTintList = cs(if (showPw) R.color.v4_accent else R.color.v4_fg3)
    }

    private fun runCheck() {
        val pw = pwField.text?.toString().orEmpty()
        if (pw.length < 4 || phase == Phase.CHECKING) return
        hideKeyboard()
        showPhase(Phase.CHECKING, animate = true)
        pwRadar.start()
        lifecycleScope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { PwnedPasswords.count(pw) } }
            pwRadar.stop()
            if (ok.isSuccess) { breachCount = ok.getOrDefault(0); phase = Phase.RESULT }
            else phase = Phase.OFFLINE
            renderResult(pw)
            showPhase(phase, animate = true)
        }
    }

    private fun reset() {
        pwField.setText("")
        breachCount = 0
        showPhase(Phase.INPUT, animate = true)
        pwField.requestFocus()
        showKeyboard()
    }

    // ───────────────────────── result ─────────────────────────

    /** Verdict model — ports the design's passwordVerdict (offline / breached / weak / strong). */
    private data class Verdict(
        val state: String, val colorRes: Int, val bgRes: Int, val icon: Int,
        val headline: String, val sub: String, val guidance: String,
    )

    private fun verdict(pw: String): Verdict {
        if (phase == Phase.OFFLINE) return Verdict(
            getString(R.string.breach_pw_state_offline), R.color.v4_fg2, R.color.v4_surf_tint, R.drawable.ic_glyph_globe,
            getString(R.string.breach_pw_offline_headline), getString(R.string.breach_pw_offline_sub),
            getString(R.string.breach_pw_offline_guidance),
        )
        val s = PasswordStrength.estimate(pw)
        return when {
            breachCount > 0 -> Verdict(
                getString(R.string.breach_pw_state_breached), R.color.v4_bad, R.color.v4_bad_bg, R.drawable.ic_glyph_warn,
                "", "", getString(R.string.breach_pw_breached_guidance),
            )
            s.level <= 1 -> Verdict(
                getString(R.string.breach_pw_state_weak), R.color.v4_warn, R.color.v4_warn_bg, R.drawable.ic_glyph_warn,
                getString(R.string.breach_pw_weak_headline), getString(R.string.breach_pw_weak_sub_fmt, s.crackTime),
                getString(R.string.breach_pw_weak_guidance),
            )
            else -> Verdict(
                getString(R.string.breach_pw_state_strong), R.color.v4_ok, R.color.v4_ok_bg, R.drawable.ic_glyph_check,
                getString(R.string.breach_pw_strong_headline), getString(R.string.breach_pw_strong_sub_fmt, s.crackTime),
                getString(R.string.breach_pw_strong_guidance),
            )
        }
    }

    private fun renderResult(pw: String) {
        val v = verdict(pw)
        val color = c(v.colorRes)
        val breached = phase == Phase.RESULT && breachCount > 0
        val offline = phase == Phase.OFFLINE

        pwVerdictDot.background = circle(color)
        pwVerdictState.text = v.state
        pwVerdictState.setTextColor(color)

        pwCountBlock.visibility = if (breached) View.VISIBLE else View.GONE
        pwHeadlineBlock.visibility = if (breached) View.GONE else View.VISIBLE
        if (breached) {
            pwBreachCount.text = "%,d".format(breachCount)
            pwBreachCount.setTextColor(color)
        } else {
            pwVerdictHeadline.text = v.headline
            pwVerdictSub.text = v.sub
        }

        // guidance card tinted by the verdict color
        pwGuidanceCard.background = GradientDrawable().apply {
            cornerRadius = dp(14f); setColor(c(v.bgRes)); setStroke(dp(1f).toInt(), withAlpha(color, 0x44))
        }
        pwGuidanceIcon.setImageResource(v.icon)
        pwGuidanceIcon.imageTintList = ColorStateList.valueOf(color)
        pwGuidanceText.text = v.guidance

        // credential-stuffing education (breached only), bolding the term
        if (breached) {
            pwCredStuffing.visibility = View.VISIBLE
            pwCredStuffing.text = boldTerm(
                getString(R.string.breach_pw_credential_stuffing),
                getString(R.string.breach_pw_cred_stuffing_term), R.color.v4_fg1,
            )
        } else pwCredStuffing.visibility = View.GONE

        // strength companion — always shown, even offline (computed on-device)
        bindMeter(meterResultView(), PasswordStrength.estimate(pw))

        // actions
        pwBtnRetry.visibility = if (offline) View.VISIBLE else View.GONE
        if (offline) styleSecondary(pwBtnAnother) else stylePrimary(pwBtnAnother)
    }

    // ───────────────────────── strength meter ─────────────────────────

    private fun meterInputView(): View =
        meterInput ?: LayoutInflater.from(this).inflate(R.layout.view_strength_meter, pwMeterSlotInput, false)
            .also { pwMeterSlotInput.addView(it); meterInput = it }

    private fun meterResultView(): View =
        meterResult ?: LayoutInflater.from(this).inflate(R.layout.view_strength_meter, pwMeterSlotResult, false)
            .also { pwMeterSlotResult.addView(it); meterResult = it }

    private fun strengthColor(level: Int): Int = c(
        when {
            level <= 0 -> R.color.v4_bad
            level == 1 -> R.color.v4_warn
            level == 2 -> R.color.v4_accent
            else -> R.color.v4_ok
        }
    )

    /** Bind a strength meter view — segment fill animates (250ms), like the design's transition. */
    private fun bindMeter(view: View, s: PasswordStrength.Strength) {
        val levelTv = view.findViewById<TextView>(R.id.pwStrengthLevel)
        val crackText = view.findViewById<TextView>(R.id.pwCrackText)
        val crackLine = view.findViewById<View>(R.id.pwCrackLine)
        val issues = view.findViewById<FlowLayout>(R.id.pwIssues)

        val empty = s.isEmpty
        val color = if (empty) c(R.color.v4_fg3) else strengthColor(s.level)
        levelTv.text = s.label
        levelTv.setTextColor(color)

        val filled = if (empty) 0 else s.level + 1
        val segs = listOf(
            view.findViewById<View>(R.id.pwSeg0), view.findViewById<View>(R.id.pwSeg1),
            view.findViewById<View>(R.id.pwSeg2), view.findViewById<View>(R.id.pwSeg3),
        )
        segs.forEachIndexed { i, seg -> animateSeg(seg, if (i < filled) color else c(R.color.v4_hairline2)) }

        crackLine.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) {
            val sb = SpannableStringBuilder(getString(R.string.breach_pw_cracks_prefix))
            val st = sb.length
            sb.append(s.crackTime)
            sb.setSpan(ForegroundColorSpan(c(R.color.v4_fg0)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            crackText.text = sb
        }

        issues.removeAllViews()
        if (!empty) s.issues.take(4).forEach { issues.addView(makeChip(it)) }
    }

    /** Animate a single segment's fill to [target] over 250ms; current color is stashed per-view
     *  as a tag so this works independently for the input meter and the result meter. */
    private fun animateSeg(seg: View, target: Int) {
        val from = (seg.getTag(R.id.pwSeg0) as? Int) ?: c(R.color.v4_hairline2)
        seg.setTag(R.id.pwSeg0, target)
        if (from == target) { seg.background = rounded(3f, target); return }
        ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 250
            addUpdateListener { seg.background = rounded(3f, it.animatedValue as Int) }
            start()
        }
    }

    private fun makeChip(text: String): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(6f); setColor(c(R.color.v4_surf3)); setStroke(dp(1f).toInt(), c(R.color.v4_hairline))
            }
            setPadding(dp(8f).toInt(), dp(3f).toInt(), dp(8f).toInt(), dp(3f).toInt())
        }
        val dot = View(this).apply { background = circle(c(R.color.v4_warn)) }
        chip.addView(dot, LinearLayout.LayoutParams(dp(4f).toInt(), dp(4f).toInt()))
        val tv = TextView(this).apply {
            this.text = text; textSize = 10.5f; setTextColor(c(R.color.v4_fg1))
        }
        chip.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(5f).toInt() })
        return chip
    }

    // ───────────────────────── phase machine + animations ─────────────────────────

    private fun showPhase(p: Phase, animate: Boolean) {
        phase = p
        val shown = when (p) {
            Phase.INPUT -> phaseInput
            Phase.CHECKING -> phaseChecking
            else -> phaseResult
        }
        for (v in listOf(phaseInput, phaseChecking, phaseResult)) v.visibility = if (v == shown) View.VISIBLE else View.GONE
        if (animate) {
            shown.alpha = 0f
            shown.translationY = dp(8f)
            shown.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else { shown.alpha = 1f; shown.translationY = 0f }
    }

    private fun animateInputBorder(target: Int) {
        if (inputBorderColor == target) return
        ValueAnimator.ofObject(ArgbEvaluator(), inputBorderColor, target).apply {
            duration = 200
            addUpdateListener { inputBoxBg?.setStroke(dp(1f).toInt(), it.animatedValue as Int) }
            start()
        }
        inputBorderColor = target
    }

    private fun showPrivacySheet() {
        val sheet = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_password_privacy, null)
        v.findViewById<TextView>(R.id.pwExplainerIntro).text = boldTerm(
            getString(R.string.breach_pw_explainer_intro),
            getString(R.string.breach_pw_explainer_kterm), R.color.v4_fg1,
        )
        v.findViewById<MaterialButton>(R.id.pwExplainerGotIt).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(v)
        (v.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.show()
    }

    // ───────────────────────── style helpers ─────────────────────────

    private fun stylePrimary(b: MaterialButton) {
        b.backgroundTintList = cs(R.color.v4_accent)
        b.setTextColor(c(R.color.v4_bg)); b.iconTint = cs(R.color.v4_bg); b.strokeWidth = 0
    }

    private fun styleSecondary(b: MaterialButton) {
        b.backgroundTintList = cs(R.color.v4_surf3)
        b.setTextColor(c(R.color.v4_fg1)); b.iconTint = cs(R.color.v4_fg1)
        b.strokeColor = cs(R.color.v4_hairline2); b.strokeWidth = dp(1f).toInt()
    }

    private fun boldTerm(full: String, term: String, colorRes: Int): CharSequence {
        val sb = SpannableStringBuilder(full)
        val i = full.indexOf(term)
        if (i >= 0) {
            sb.setSpan(StyleSpan(Typeface.BOLD), i, i + term.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(c(colorRes)), i, i + term.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun tile(radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp); setColor(c(R.color.v4_accent_bg)); setStroke(dp(1f).toInt(), c(R.color.v4_accent_ring))
    }

    private fun rounded(radiusDp: Float, color: Int): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dp(radiusDp); setColor(color) }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)
    private fun c(res: Int): Int = ContextCompat.getColor(this, res)
    private fun cs(res: Int): ColorStateList = ColorStateList.valueOf(c(res))
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun showKeyboard() {
        pwField.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(pwField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(pwField.windowToken, 0)
    }

    @Suppress("DEPRECATION")
    private fun slideBack() = overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PasswordCheckActivity::class.java))
            if (context is android.app.Activity) {
                @Suppress("DEPRECATION")
                context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
        }
    }
}
