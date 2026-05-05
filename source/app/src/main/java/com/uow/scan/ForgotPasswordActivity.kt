package com.uow.scan

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    private lateinit var btnBack: ImageButton
    private lateinit var ivHeroIcon: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    private lateinit var stepOne: LinearLayout
    private lateinit var stepTwo: LinearLayout

    private lateinit var etEmail: EditText
    private lateinit var ivEmailIcon: ImageView
    private lateinit var tvEmailFeedback: TextView
    private lateinit var btnSend: MaterialButton
    private lateinit var tvBackToSignIn1: TextView

    private lateinit var tvResendStatus: TextView
    private lateinit var tvResendAction: TextView
    private lateinit var btnBackToSignIn: MaterialButton
    private lateinit var tvWrongEmail: TextView

    private var step = 1
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        auth = FirebaseAuth.getInstance()

        bindViews()
        setupListeners()
        renderEmailFeedback()
        renderSendEnabled()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        ivHeroIcon = findViewById(R.id.ivHeroIcon)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        stepOne = findViewById(R.id.stepOneContainer)
        stepTwo = findViewById(R.id.stepTwoContainer)

        etEmail = findViewById(R.id.etEmail)
        ivEmailIcon = findViewById(R.id.ivEmailIcon)
        tvEmailFeedback = findViewById(R.id.tvEmailFeedback)
        btnSend = findViewById(R.id.btnSend)
        tvBackToSignIn1 = findViewById(R.id.tvBackToSignIn1)

        tvResendStatus = findViewById(R.id.tvResendStatus)
        tvResendAction = findViewById(R.id.tvResendAction)
        btnBackToSignIn = findViewById(R.id.btnBackToSignIn)
        tvWrongEmail = findViewById(R.id.tvWrongEmail)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { goBack() }
        tvBackToSignIn1.setOnClickListener { goBack() }
        btnBackToSignIn.setOnClickListener { goBack() }

        btnSend.setOnClickListener { submit() }

        tvWrongEmail.setOnClickListener {
            step = 1
            renderStep()
            etEmail.requestFocus()
        }

        tvResendAction.setOnClickListener { resend() }

        etEmail.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                renderEmailFeedback()
                renderSendEnabled()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etEmail.setOnFocusChangeListener { _, hasFocus ->
            val colorRes = if (hasFocus) R.color.v4_accent else R.color.v4_fg3
            ivEmailIcon.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        }
    }

    private fun goBack() {
        finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun emailValid(): Boolean {
        val email = etEmail.text.toString().trim()
        return Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun renderEmailFeedback() {
        val email = etEmail.text.toString().trim()
        when {
            email.isEmpty() -> {
                tvEmailFeedback.setText(R.string.fp_email_hint)
                tvEmailFeedback.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                tvEmailFeedback.setText(R.string.fp_email_error)
                tvEmailFeedback.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
            }
            else -> {
                tvEmailFeedback.setText(R.string.fp_email_hint)
                tvEmailFeedback.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
            }
        }
    }

    private fun renderSendEnabled() {
        btnSend.isEnabled = emailValid() && !sending
    }

    private fun renderStep() {
        if (step == 1) {
            stepOne.visibility = View.VISIBLE
            stepTwo.visibility = View.GONE
            ivHeroIcon.setImageResource(R.drawable.ic_glyph_lock)
            tvTitle.setText(R.string.fp_step1_title)
            tvSubtitle.setText(R.string.fp_step1_subtitle)
        } else {
            stepOne.visibility = View.GONE
            stepTwo.visibility = View.VISIBLE
            ivHeroIcon.setImageResource(R.drawable.ic_glyph_check)
            tvTitle.setText(R.string.fp_step2_title)
            tvSubtitle.text = buildSpannedSubtitle()
        }
    }

    private fun buildSpannedSubtitle(): CharSequence {
        val email = etEmail.text.toString().trim()
        val prefix = getString(R.string.fp_step2_subtitle_prefix)
        val suffix = getString(R.string.fp_step2_subtitle_suffix)
        val full = "$prefix$email$suffix"
        val ssb = android.text.SpannableStringBuilder(full)
        val emailStart = prefix.length
        val emailEnd = emailStart + email.length
        if (email.isNotEmpty()) {
            ssb.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.v4_fg0)),
                emailStart, emailEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                emailStart, emailEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return ssb
    }

    private fun submit() {
        if (!emailValid() || sending) return
        val email = etEmail.text.toString().trim()
        sending = true
        btnSend.setText(R.string.fp_sending)
        btnSend.icon = null
        renderSendEnabled()

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                sending = false
                btnSend.setText(R.string.fp_send_cta)
                btnSend.setIconResource(R.drawable.ic_glyph_arrow_right)
                renderSendEnabled()

                val ex = task.exception
                if (task.isSuccessful || ex !is FirebaseNetworkException) {
                    // Per the design's privacy promise: never reveal whether the
                    // email is registered. Show success on user-not-found and on
                    // every Firebase auth-side error - only surface a real error
                    // when the network is actually unreachable.
                    step = 2
                    renderStep()
                } else {
                    tvEmailFeedback.setText(R.string.fp_network_error)
                    tvEmailFeedback.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
                }
            }
    }

    private fun resend() {
        val email = etEmail.text.toString().trim()
        if (email.isEmpty()) return
        tvResendAction.isClickable = false
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener {
                tvResendStatus.setText(R.string.fp_resend_done)
                tvResendAction.setText(R.string.fp_resend_done_pill)
                tvResendAction.setTextColor(ContextCompat.getColor(this, R.color.v4_ok))
                tvResendStatus.postDelayed({
                    tvResendStatus.setText(R.string.fp_resend_default)
                    tvResendAction.setText(R.string.fp_resend_action)
                    tvResendAction.setTextColor(ContextCompat.getColor(this, R.color.v4_accent))
                    tvResendAction.isClickable = true
                }, 2400)
            }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
