package com.uow.scan

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.uow.scan.util.GoogleProfilePhotoFetcher
import com.uow.scan.util.PreferencesManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var ivEmailIcon: ImageView
    private lateinit var ivPasswordIcon: ImageView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnGoogle: View
    private lateinit var btnTogglePassword: ImageButton
    private lateinit var cbRememberWrap: View
    private lateinit var cbRemember: View
    private lateinit var ivRememberCheck: ImageView
    private lateinit var tvForgotPassword: View
    private lateinit var tvSignUp: View
    private lateinit var progressBar: ProgressBar

    private var isPasswordVisible = false
    private var rememberMe = true

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            // Capture the Google profile photo at the moment of sign-in. Firebase doesn't
            // always propagate it onto FirebaseUser.photoUrl, so we mirror it into prefs
            // so Settings/Profile can render the avatar reliably across sessions.
            //
            // GoogleSignInAccount.getPhotoUrl() comes back null on some Google account
            // configurations even with the profile scope. Fall back to parsing the
            // `picture` claim from the ID token JWT — Google always includes it there.
            val resolvedPhoto = account?.photoUrl?.toString()
                ?: account?.idToken?.let { extractPictureClaim(it) }
            if (!resolvedPhoto.isNullOrBlank()) {
                com.uow.scan.util.PreferencesManager.setGoogleProfilePhotoUrl(this, resolvedPhoto)
            }
            account?.idToken?.let { firebaseAuthWithGoogle(it) }
        } catch (e: ApiException) {
            showLoading(false)
            Toast.makeText(this, "Google sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()

        // requestProfile() forces the profile scope so the GoogleSignInAccount
        // returned by the picker carries getPhotoUrl()/getDisplayName() reliably —
        // DEFAULT_SIGN_IN includes the scope but the data comes back null on some
        // Google account configurations unless we ask for it explicitly.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestProfile()
            .requestScopes(com.google.android.gms.common.api.Scope("profile"))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        initViews()
        loadRememberMe()
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        if (rememberMe) {
            auth.currentUser?.let { navigateToNextScreen() }
        } else if (auth.currentUser != null) {
            auth.signOut()
            googleSignInClient.signOut()
        }
    }

    private fun initViews() {
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        ivEmailIcon = findViewById(R.id.ivEmailIcon)
        ivPasswordIcon = findViewById(R.id.ivPasswordIcon)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoogle = findViewById(R.id.btnGoogle)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        cbRememberWrap = findViewById(R.id.cbRememberWrap)
        cbRemember = findViewById(R.id.cbRemember)
        ivRememberCheck = findViewById(R.id.ivRememberCheck)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvSignUp = findViewById(R.id.tvSignUp)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun loadRememberMe() {
        val prefs = getSharedPreferences(LOGIN_PREFS, Context.MODE_PRIVATE)
        rememberMe = prefs.getBoolean(KEY_REMEMBER_ME, true)
        renderRememberMe()
    }

    private fun saveRememberMe() {
        getSharedPreferences(LOGIN_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REMEMBER_ME, rememberMe).apply()
    }

    private fun renderRememberMe() {
        cbRemember.isSelected = rememberMe
        ivRememberCheck.visibility = if (rememberMe) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()

            if (validateInput(email, password)) {
                signInWithEmail(email, password)
            }
        }

        btnGoogle.setOnClickListener { signInWithGoogle() }

        cbRememberWrap.setOnClickListener {
            rememberMe = !rememberMe
            renderRememberMe()
            saveRememberMe()
        }

        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        tvSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        etEmail.setOnFocusChangeListener { _, hasFocus ->
            updateFieldIconTint(ivEmailIcon, hasFocus)
        }
        etPassword.setOnFocusChangeListener { _, hasFocus ->
            updateFieldIconTint(ivPasswordIcon, hasFocus)
        }
    }

    private fun updateFieldIconTint(icon: ImageView, focused: Boolean) {
        val colorRes = if (focused) R.color.v4_accent else R.color.v4_fg3
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun validateInput(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            etEmail.error = "Email is required"
            etEmail.requestFocus()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Please enter a valid email"
            etEmail.requestFocus()
            return false
        }

        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            etPassword.requestFocus()
            return false
        }

        if (password.length < 6) {
            etPassword.error = "Password must be at least 6 characters"
            etPassword.requestFocus()
            return false
        }

        return true
    }

    private fun signInWithEmail(email: String, password: String) {
        showLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    saveRememberMe()
                    navigateToNextScreen()
                } else {
                    Toast.makeText(
                        this,
                        "Login failed: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun signInWithGoogle() {
        showLoading(true)
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                showLoading(false)
                if (task.isSuccessful) {
                    saveRememberMe()
                    // Pull the photo URL through Google's userinfo endpoint as a backstop
                    // for accounts where photoUrl/JWT picture aren't surfaced. Fire-and-forget.
                    lifecycleScope.launch { GoogleProfilePhotoFetcher.refresh(this@LoginActivity) }
                    navigateToNextScreen()
                } else {
                    Toast.makeText(
                        this,
                        "Authentication failed: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        if (isPasswordVisible) {
            etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
            btnTogglePassword.setImageResource(R.drawable.ic_glyph_eye_off)
            btnTogglePassword.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.v4_accent))
        } else {
            etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
            btnTogglePassword.setImageResource(R.drawable.ic_glyph_eye)
            btnTogglePassword.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.v4_fg3))
        }
        etPassword.setSelection(etPassword.text.length)
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !show
        btnGoogle.isEnabled = !show
    }

    private fun navigateToNextScreen() {
        val intent = if (PreferencesManager.isOnboardingComplete(this)) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, PermissionsActivity::class.java)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }

    /**
     * Pulls the `picture` claim out of a Google ID token JWT. The token is three
     * base64url segments joined by dots; the middle segment is the JSON payload.
     * Returns null on any parse failure (we treat the photo as best-effort).
     */
    private fun extractPictureClaim(idToken: String): String? = runCatching {
        val parts = idToken.split('.')
        if (parts.size < 2) return@runCatching null
        val payload = String(
            android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        )
        org.json.JSONObject(payload).optString("picture", "").takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val LOGIN_PREFS = "login_prefs"
        private const val KEY_REMEMBER_ME = "remember_me"
    }
}
