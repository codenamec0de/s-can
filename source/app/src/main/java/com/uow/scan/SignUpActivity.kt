package com.uow.scan

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    // Top bar / progress
    private lateinit var btnBack: ImageButton
    private lateinit var tvStepCurrent: TextView
    private lateinit var progressSeg2: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView

    // Step 1
    private lateinit var stepOne: LinearLayout
    private lateinit var etName: EditText
    private lateinit var etEmail: EditText
    private lateinit var ivNameIcon: ImageView
    private lateinit var ivEmailIcon: ImageView
    private lateinit var tvEmailHint: TextView
    private lateinit var btnContinue: MaterialButton
    private lateinit var btnGoogle: View
    private lateinit var tvSignIn: TextView

    // Step 2
    private lateinit var stepTwo: LinearLayout
    private lateinit var etPassword: EditText
    private lateinit var etPasswordConfirm: EditText
    private lateinit var ivPasswordIcon: ImageView
    private lateinit var ivConfirmIcon: ImageView
    private lateinit var ivConfirmCheck: ImageView
    private lateinit var btnTogglePassword: ImageButton
    private lateinit var tvStrengthLabel: TextView
    private lateinit var tvConfirmHint: TextView
    private lateinit var agreementWrap: View
    private lateinit var cbAgree: View
    private lateinit var ivAgreeCheck: ImageView
    private lateinit var btnCreate: MaterialButton
    private lateinit var strengthSegs: List<View>

    private lateinit var progressBar: ProgressBar

    private var step = 1
    private var isPasswordVisible = false
    private var agreed = false

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.idToken?.let { firebaseAuthWithGoogle(it) }
        } catch (e: ApiException) {
            showLoading(false)
            Toast.makeText(this, "Google sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        bindViews()
        setupListeners()
        renderStep()
        renderEmailFeedback()
        renderStrength("")
        renderConfirmFeedback()
        renderContinueEnabled()
        renderCreateEnabled()
    }

    private fun bindViews() {
        btnBack = findViewById(R.id.btnBack)
        tvStepCurrent = findViewById(R.id.tvStepCurrent)
        progressSeg2 = findViewById(R.id.progressSeg2)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)

        stepOne = findViewById(R.id.stepOneContainer)
        etName = findViewById(R.id.etName)
        etEmail = findViewById(R.id.etEmail)
        ivNameIcon = findViewById(R.id.ivNameIcon)
        ivEmailIcon = findViewById(R.id.ivEmailIconSu)
        tvEmailHint = findViewById(R.id.tvEmailHint)
        btnContinue = findViewById(R.id.btnContinue)
        btnGoogle = findViewById(R.id.btnGoogle)
        tvSignIn = findViewById(R.id.tvSignIn)

        stepTwo = findViewById(R.id.stepTwoContainer)
        etPassword = findViewById(R.id.etPassword)
        etPasswordConfirm = findViewById(R.id.etPasswordConfirm)
        ivPasswordIcon = findViewById(R.id.ivPasswordIcon)
        ivConfirmIcon = findViewById(R.id.ivConfirmIcon)
        ivConfirmCheck = findViewById(R.id.ivConfirmCheck)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        tvStrengthLabel = findViewById(R.id.tvStrengthLabel)
        tvConfirmHint = findViewById(R.id.tvConfirmHint)
        agreementWrap = findViewById(R.id.agreementWrap)
        cbAgree = findViewById(R.id.cbAgree)
        ivAgreeCheck = findViewById(R.id.ivAgreeCheck)
        btnCreate = findViewById(R.id.btnCreate)
        strengthSegs = listOf(
            findViewById(R.id.strengthSeg1),
            findViewById(R.id.strengthSeg2),
            findViewById(R.id.strengthSeg3),
            findViewById(R.id.strengthSeg4),
        )

        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            if (step == 2) {
                step = 1
                renderStep()
            } else {
                finish()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
        }

        tvSignIn.setOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        btnContinue.setOnClickListener {
            if (step1Valid()) {
                step = 2
                renderStep()
            }
        }

        btnGoogle.setOnClickListener { signUpWithGoogle() }

        btnCreate.setOnClickListener {
            if (step2Valid()) {
                createAccount()
            }
        }

        btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        agreementWrap.setOnClickListener {
            agreed = !agreed
            renderAgreement()
            renderCreateEnabled()
        }

        etName.addTextChangedListener(simpleWatcher { renderContinueEnabled() })
        etEmail.addTextChangedListener(simpleWatcher {
            renderEmailFeedback()
            renderContinueEnabled()
        })
        etPassword.addTextChangedListener(simpleWatcher {
            renderStrength(etPassword.text.toString())
            renderConfirmFeedback()
            renderCreateEnabled()
        })
        etPasswordConfirm.addTextChangedListener(simpleWatcher {
            renderConfirmFeedback()
            renderCreateEnabled()
        })

        etName.setOnFocusChangeListener { _, hasFocus -> updateIconTint(ivNameIcon, hasFocus) }
        etEmail.setOnFocusChangeListener { _, hasFocus -> updateIconTint(ivEmailIcon, hasFocus) }
        etPassword.setOnFocusChangeListener { _, hasFocus -> updateIconTint(ivPasswordIcon, hasFocus) }
        etPasswordConfirm.setOnFocusChangeListener { _, hasFocus -> updateIconTint(ivConfirmIcon, hasFocus) }
    }

    private fun renderStep() {
        tvStepCurrent.text = step.toString()
        if (step == 1) {
            stepOne.visibility = View.VISIBLE
            stepTwo.visibility = View.GONE
            tvTitle.setText(R.string.su_step1_title)
            tvSubtitle.setText(R.string.su_step1_subtitle)
            progressSeg2.background = ContextCompat.getDrawable(this, R.drawable.bg_v4_strength_segment)
            etName.requestFocus()
        } else {
            stepOne.visibility = View.GONE
            stepTwo.visibility = View.VISIBLE
            tvTitle.setText(R.string.su_step2_title)
            tvSubtitle.setText(R.string.su_step2_subtitle)
            progressSeg2.background = ContextCompat.getDrawable(this, R.drawable.bg_v4_perm_progress_fill)
            etPassword.requestFocus()
        }
    }

    private fun renderEmailFeedback() {
        val email = etEmail.text.toString().trim()
        when {
            email.isEmpty() -> {
                tvEmailHint.setText(R.string.su_email_hint)
                tvEmailHint.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                tvEmailHint.setText(R.string.su_email_error)
                tvEmailHint.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
            }
            else -> {
                tvEmailHint.setText(R.string.su_email_hint)
                tvEmailHint.setTextColor(ContextCompat.getColor(this, R.color.v4_fg3))
            }
        }
    }

    private fun renderStrength(password: String) {
        val score = passwordScore(password)
        val labelRes = when {
            password.isEmpty() -> R.string.su_strength_default
            score == 0 -> R.string.su_strength_too_short
            score == 1 -> R.string.su_strength_weak
            score == 2 -> R.string.su_strength_fair
            score == 3 -> R.string.su_strength_strong
            else -> R.string.su_strength_excellent
        }
        val colorRes = when {
            password.isEmpty() -> R.color.v4_fg3
            score == 0 -> R.color.v4_fg4
            score == 1 -> R.color.v4_bad
            score == 2 -> R.color.v4_warn
            score == 3 -> R.color.v4_ok
            else -> R.color.v4_accent
        }
        val color = ContextCompat.getColor(this, colorRes)
        tvStrengthLabel.setText(labelRes)
        tvStrengthLabel.setTextColor(
            ContextCompat.getColor(this, if (password.isEmpty()) R.color.v4_fg3 else colorRes)
        )
        strengthSegs.forEachIndexed { i, view ->
            if (i < score) {
                view.background = filledSegment(color)
            } else {
                view.background = ContextCompat.getDrawable(this, R.drawable.bg_v4_strength_segment)
            }
        }
    }

    private fun filledSegment(color: Int): android.graphics.drawable.GradientDrawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = 2 * resources.displayMetrics.density
        d.setColor(color)
        return d
    }

    private fun passwordScore(password: String): Int {
        var s = 0
        if (password.length >= 8) s++
        if (password.length >= 12) s++
        if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) s++
        if (password.any { it.isDigit() }) s++
        if (password.any { !it.isLetterOrDigit() }) s++
        return minOf(s, 4)
    }

    private fun renderConfirmFeedback() {
        val pw = etPassword.text.toString()
        val confirm = etPasswordConfirm.text.toString()
        when {
            confirm.isEmpty() -> {
                tvConfirmHint.visibility = View.GONE
                ivConfirmCheck.visibility = View.GONE
            }
            confirm == pw -> {
                tvConfirmHint.visibility = View.VISIBLE
                tvConfirmHint.setText(R.string.su_confirm_match)
                tvConfirmHint.setTextColor(ContextCompat.getColor(this, R.color.v4_ok))
                ivConfirmCheck.visibility = View.VISIBLE
            }
            else -> {
                tvConfirmHint.visibility = View.VISIBLE
                tvConfirmHint.setText(R.string.su_confirm_mismatch)
                tvConfirmHint.setTextColor(ContextCompat.getColor(this, R.color.v4_bad))
                ivConfirmCheck.visibility = View.GONE
            }
        }
    }

    private fun renderAgreement() {
        cbAgree.isSelected = agreed
        ivAgreeCheck.visibility = if (agreed) View.VISIBLE else View.GONE
    }

    private fun renderContinueEnabled() {
        btnContinue.isEnabled = step1Valid()
    }

    private fun renderCreateEnabled() {
        btnCreate.isEnabled = step2Valid()
    }

    private fun step1Valid(): Boolean {
        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        return name.length >= 2 && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun step2Valid(): Boolean {
        val pw = etPassword.text.toString()
        val confirm = etPasswordConfirm.text.toString()
        return pw.length >= 8 && pw == confirm && agreed
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        val transformation = if (isPasswordVisible)
            HideReturnsTransformationMethod.getInstance()
        else
            PasswordTransformationMethod.getInstance()
        etPassword.transformationMethod = transformation
        etPasswordConfirm.transformationMethod = transformation
        btnTogglePassword.setImageResource(
            if (isPasswordVisible) R.drawable.ic_glyph_eye_off else R.drawable.ic_glyph_eye
        )
        btnTogglePassword.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isPasswordVisible) R.color.v4_accent else R.color.v4_fg3)
        )
        etPassword.setSelection(etPassword.text.length)
        etPasswordConfirm.setSelection(etPasswordConfirm.text.length)
    }

    private fun updateIconTint(icon: ImageView, focused: Boolean) {
        val colorRes = if (focused) R.color.v4_accent else R.color.v4_fg3
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun createAccount() {
        val name = etName.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        showLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val updates = UserProfileChangeRequest.Builder()
                        .setDisplayName(name)
                        .build()
                    auth.currentUser?.updateProfile(updates)
                        ?.addOnCompleteListener {
                            showLoading(false)
                            navigateToPermissions()
                        } ?: run {
                            showLoading(false)
                            navigateToPermissions()
                        }
                } else {
                    showLoading(false)
                    Toast.makeText(
                        this,
                        "Registration failed: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun signUpWithGoogle() {
        showLoading(true)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    navigateToPermissions()
                } else {
                    Toast.makeText(
                        this,
                        "Authentication failed: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun navigateToPermissions() {
        val intent = Intent(this, PermissionsActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnContinue.isEnabled = !show && step1Valid()
        btnCreate.isEnabled = !show && step2Valid()
        btnGoogle.isEnabled = !show
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (step == 2) {
            step = 1
            renderStep()
        } else {
            super.onBackPressed()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    private fun simpleWatcher(action: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { action() }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }
}
